"""On-device-grade two-stage NSFW pipeline, run server-side.

Implements the architecture from the NSFW Pretrained Model Integration Migration Plan (successor
to the original NSFW Screenshot Classifier Integration Plan): a fast Stage 1 binary classifier
(Falconsai nsfw_image_detection_26) over the full screenshot plus overlapping tiles, escalating
ambiguous/high-risk regions to a Stage 2 nudity object detector (NudeNet 640m), fused by an
explicit policy rather than an averaged score. See `nsfw_image_classifier.py`, which calls this
pipeline directly and reports classification unavailable (fails open) if it isn't ready.

Deliberately server-side, not on-device (guardian/product decision: keep screenshot classification
off the phone entirely to save battery). Only `onnxruntime`, `numpy`, and `Pillow` are required at
runtime -- no torch/HF/nudenet package.

MODEL FILES ARE NOT BUNDLED. This module is a pluggable scaffold: `initialize()` loads whatever
`.onnx` files are configured at NSFW_CLASSIFIER_PATH / NSFW_DETECTOR_PATH and leaves the pipeline
NOT READY (screenshot classification unavailable) until both exist, validate, and load successfully. See
"Bringing your own models" below for exactly what to drop in -- obtaining the actual artifacts
(accepting Falconsai's gated-repo terms, downloading NudeNet 640m, recording their licenses) is a
manual, one-time operator task this module cannot perform for you: it requires a Hugging Face
token/access grant this process is never given (see "Do not bake sensitive model credentials into
the image" below) and a license/redistribution review that is a policy decision, not a code change.

Bringing your own models
-------------------------
Stage 1 -- Falconsai/nsfw_image_detection_26 (https://huggingface.co/Falconsai/nsfw_image_detection_26):
  1. Accept the repository's gated access conditions with your own HF account, then download the
     repo's *own* supplied ONNX (or quantized-ONNX) artifact rather than converting the
     Safetensors/PyTorch checkpoint yourself -- prefer what upstream shipped and validated.
  2. Verify against that specific artifact's own config (do not assume): input tensor name/shape/
     dtype (expected: NCHW float32, 1x3x224x224), preprocessing (resize/crop, RGB order,
     normalization mean/std/scaling range), output tensor name/shape, and label order.
     CONFIRMED 2026-08-27 against this deployment's actual config.json
     (`Falconsai/nsfw_image_detection_V1.5`, the checkpoint `nsfw_image_detection_26`'s ONNX
     export is derived from): `id2label {"0": "nsfw", "1": "normal"}` -- the REVERSE of the older
     `Falconsai/nsfw_image_detection` model card's {0: "normal", 1: "nsfw"}. Caught empirically
     first (this app's own SFW logo scored 1.0 "nsfw" under the wrong index) and only then
     confirmed against the real config.json -- don't skip that confirmation step for a future model
     swap just because a flipped index is "the obvious guess"; verify it every time (see
     `validate_stage1_metadata`, and STAGE1_NSFW_CLASS_INDEX's default below).
  3. Place the file at NSFW_CLASSIFIER_PATH (default filter-server/models/nsfw_classifier.onnx)
     and set NSFW_CLASSIFIER_VERSION to the exact upstream revision/commit you exported from --
     never leave it as a floating "latest".

Stage 2 -- NudeNet 640m (https://github.com/notAI-tech/nudenet):
  1. Download the 640m ONNX artifact (the bundled default is 320n; 640m is a separate,
     higher-accuracy download) and confirm its license/redistribution terms.
  2. Verify its input shape (NCHW float32, 1x3x640x640 at NSFW_DETECTOR_INPUT_SIZE=640) and its
     class order against the artifact's own metadata/label list -- STAGE2_CLASS_NAMES below is
     NudeNet's published order and must be confirmed, not assumed (see
     `validate_stage2_metadata`).
  3. Place the file at NSFW_DETECTOR_PATH (default filter-server/models/nudity_detector.onnx) and set
     NSFW_DETECTOR_VERSION similarly.

Optional but recommended -- pin both with a manifest (section 9.2 of the plan): drop a
`models/manifest.json` shaped like:
    {"stage1": {"sha256": "..."}, "stage2": {"sha256": "..."}}
`initialize()` verifies each configured file's SHA-256 against the manifest before loading, and
fails closed (pipeline NOT READY, classification stays unavailable) on a mismatch -- this is what
catches an accidental/unauthorized model swap silently changing moderation behavior.

Neither of the above -- nor picking a real threshold -- is something this file can do for you.
Sections 14/15/16 of the plan (build a golden integration set, measure real latency/memory, roll
out gradually) are required manual steps before relying on this path in production; the defaults
below are placeholders, not calibrated values.
"""
from __future__ import annotations

import hashlib
import io
import json
import logging
import os
import time
from dataclasses import dataclass, field

log = logging.getLogger("otterling.onnx_nsfw_pipeline")

# --- Enablement -----------------------------------------------------------------------------
ONNX_ENABLED = os.environ.get("NSFW_ONNX_ENABLED", "true").strip().lower() not in ("0", "false", "no")
CLASSIFIER_ENABLED = os.environ.get("NSFW_CLASSIFIER_ENABLED", "true").strip().lower() not in ("0", "false", "no")
DETECTOR_ENABLED = os.environ.get("NSFW_DETECTOR_ENABLED", "true").strip().lower() not in ("0", "false", "no")

# --- Model files & versioning (section 9.2/12 -- pin exact revision/hash, never "latest") ------
MODELS_DIR = os.environ.get("NSFW_MODELS_DIR", "/opt/otterling/models")
CLASSIFIER_MODEL_NAME = os.environ.get("NSFW_CLASSIFIER_MODEL", "Falconsai/nsfw_image_detection_26")
CLASSIFIER_PATH = os.environ.get("NSFW_CLASSIFIER_PATH", os.path.join(MODELS_DIR, "nsfw_classifier.onnx"))
CLASSIFIER_VERSION = os.environ.get("NSFW_CLASSIFIER_VERSION", "unpinned")
DETECTOR_MODEL_NAME = os.environ.get("NSFW_DETECTOR_MODEL", "NudeNet-640m")
DETECTOR_PATH = os.environ.get("NSFW_DETECTOR_PATH", os.path.join(MODELS_DIR, "nudity_detector.onnx"))
DETECTOR_VERSION = os.environ.get("NSFW_DETECTOR_VERSION", "unpinned")
# Optional models/manifest.json -- see "Bringing your own models" above. Absent = no hash pinning
# enforced beyond the *_VERSION strings (still logged, still not "latest", but unverified against
# the actual file bytes).
MANIFEST_PATH = os.environ.get("NSFW_MODEL_MANIFEST_PATH", os.path.join(MODELS_DIR, "manifest.json"))
POLICY_VERSION = os.environ.get("NSFW_POLICY_VERSION", "v1")

# --- Stage 1 preprocessing (must match the actual downloaded artifact -- see module docstring) --
STAGE1_INPUT_SIZE = int(os.environ.get("NSFW_CLASSIFIER_INPUT_SIZE", "224"))
STAGE1_MEAN = float(os.environ.get("NSFW_CLASSIFIER_MEAN", "0.5"))
STAGE1_STD = float(os.environ.get("NSFW_CLASSIFIER_STD", "0.5"))
# Index of the "nsfw" class in the model's logits. Default 0 matches this deployment's actual
# config.json (id2label {"0": "nsfw", "1": "normal"}, confirmed 2026-08-27 -- see module docstring)
# -- override via env if a future model swap's id2label differs; verify against that artifact's own
# config, don't assume.
STAGE1_NSFW_CLASS_INDEX = int(os.environ.get("NSFW_CLASSIFIER_NSFW_CLASS_INDEX", "0"))
STAGE1_EXPECTED_LABEL_COUNT = 2

# --- Stage 2 preprocessing / classes -------------------------------------------------------------
STAGE2_INPUT_SIZE = int(os.environ.get("NSFW_DETECTOR_INPUT_SIZE", "640"))
# Published NudeNet class order (shared across the 320n/640m checkpoints). Verify against the
# specific exported artifact -- see `validate_stage2_metadata`.
STAGE2_CLASS_NAMES = [
    "FEMALE_GENITALIA_COVERED", "FACE_FEMALE", "BUTTOCKS_EXPOSED", "FEMALE_BREAST_EXPOSED",
    "FEMALE_GENITALIA_EXPOSED", "MALE_BREAST_EXPOSED", "ANUS_EXPOSED", "FEET_EXPOSED",
    "BELLY_COVERED", "FEET_COVERED", "ARMPITS_COVERED", "ARMPITS_EXPOSED", "FACE_MALE",
    "BELLY_EXPOSED", "MALE_GENITALIA_EXPOSED", "ANUS_COVERED", "FEMALE_BREAST_COVERED",
    "BUTTOCKS_COVERED",
]
# Strong evidence (section 5.3): alone, at NSFW_STAGE2_STRONG_THRESHOLD confidence, is sufficient
# to BLOCK regardless of Stage 1. Deliberately excludes faces/feet/armpits/belly/*_COVERED -- a
# detected face or exposed feet must never independently trigger a parental-control block.
STAGE2_EXPLICIT_CLASSES = frozenset({
    "FEMALE_GENITALIA_EXPOSED", "MALE_GENITALIA_EXPOSED", "ANUS_EXPOSED",
    "FEMALE_BREAST_EXPOSED", "BUTTOCKS_EXPOSED",
})
# Weak/contextual evidence: never blocks alone, only ever corroborates (see fusion policy).
STAGE2_WEAK_CLASSES = frozenset({
    "BELLY_EXPOSED", "ARMPITS_EXPOSED", "FEET_EXPOSED", "FACE_FEMALE", "FACE_MALE",
    "FEMALE_GENITALIA_COVERED", "BELLY_COVERED", "FEET_COVERED", "ARMPITS_COVERED",
    "ANUS_COVERED", "FEMALE_BREAST_COVERED", "BUTTOCKS_COVERED",
})
# Detector's own score/NMS cutoffs (section 5.4) -- distinct from the fusion-layer thresholds below.
DETECTOR_SCORE_THRESHOLD = float(os.environ.get("NSFW_DETECTOR_SCORE_THRESHOLD", "0.5"))
DETECTOR_NMS_THRESHOLD = float(os.environ.get("NSFW_DETECTOR_NMS_THRESHOLD", "0.45"))

# --- Region extraction (section 6) ----------------------------------------------------------------
TILE_SIZE = int(os.environ.get("NSFW_TILE_SIZE", "768"))
# Fraction of a tile's size that consecutive tiles overlap by, so explicit content sitting on a
# tile boundary is never split across two weak inputs.
TILE_OVERLAP_FRACTION = float(os.environ.get("NSFW_TILE_OVERLAP", "0.20"))
# Screenshots at or below this on-screen size are classified whole (single "tile") -- tiling a
# small image just re-crops the same content into overlapping near-duplicates for no benefit.
TILE_MIN_IMAGE_DIMENSION = int(os.environ.get("NSFW_TILE_MIN_IMAGE_DIMENSION", TILE_SIZE * 2))
# Hard cap (section 6.3) -- protects latency/CPU on a home server from a pathological aspect ratio
# (e.g. a very tall scrolling-capture screenshot) generating hundreds of tiles.
MAX_TILES = int(os.environ.get("NSFW_MAX_TILES", "24"))

# --- Fusion thresholds (section 7 -- PLACEHOLDERS, calibrate against a real golden/benchmark set
# before production use) ---------------------------------------------------------------------------
STAGE1_HIGH_THRESHOLD = float(os.environ.get("NSFW_STAGE1_HIGH_THRESHOLD", "0.95"))
STAGE2_STRONG_THRESHOLD = float(os.environ.get("NSFW_STAGE2_STRONG_THRESHOLD", "0.70"))
# Also doubles as the Stage 1 escalation trigger (§6.2: "ambiguous/high-risk" regions run Stage 2)
# and as the "another region corroborates" bar in the STAGE1_HIGH_THRESHOLD-alone case below.
STAGE2_GENERAL_THRESHOLD = float(os.environ.get("NSFW_STAGE2_GENERAL_THRESHOLD", "0.50"))
# How an internal UNCERTAIN verdict (section 7.3 -- e.g. a lone very-high Stage 1 score with no
# corroborating region and no Stage 2 evidence) maps to the boolean callers need. Parental-control
# products should weigh false negatives over false positives (see the original plan's section 7),
# so this defaults to the conservative side; an operator with calibration data may override.
UNCERTAIN_POLICY = os.environ.get("NSFW_UNCERTAIN_POLICY", "block").strip().lower()

DECISION_ALLOW = "ALLOW"
DECISION_BLOCK = "BLOCK"
DECISION_UNCERTAIN = "UNCERTAIN"


@dataclass
class RegionResult:
    bounds: tuple[int, int, int, int]  # (left, top, right, bottom) in original-image pixels
    tile_index: int
    stage1_score: float
    stage2_detections: list[dict] = field(default_factory=list)


@dataclass
class PipelineResult:
    decision: str  # DECISION_ALLOW | DECISION_BLOCK | DECISION_UNCERTAIN
    nsfw_score: float
    regions: list[RegionResult]
    stage1_model_version: str
    stage2_model_version: str
    policy_version: str
    processing_time_ms: int
    tile_count: int
    detection_count: int

    def diagnostics(self) -> dict:
        """Section 11's recommended internal structure -- aggregate metadata only, never image
        bytes/paths (section 12)."""
        detections = [
            {"class": d["label"], "score": d["confidence"], "tile": region.tile_index}
            for region in self.regions
            for d in region.stage2_detections
        ]
        return {
            "decision": self.decision,
            "confidence": self.nsfw_score,
            "classifier": {"model": CLASSIFIER_MODEL_NAME, "version": self.stage1_model_version, "score": self.nsfw_score},
            "detector": {"model": DETECTOR_MODEL_NAME, "version": self.stage2_model_version, "detections": detections},
            "tile_count": self.tile_count,
            "detection_count": self.detection_count,
            "processing_time_ms": self.processing_time_ms,
            "policy_version": self.policy_version,
        }


class PipelineUnavailableError(Exception):
    """Raised when onnxruntime/numpy/Pillow, model files, or manifest/metadata validation fail --
    callers must treat this as "try the fallback classifier", never as a classification verdict."""


class ModelValidationError(PipelineUnavailableError):
    """Raised by validate_stage*_metadata when a loaded model's actual input/output/label shape
    doesn't match what this module assumes -- fails closed rather than silently misinterpreting a
    different model's output as an NSFW score (section 8.1: "fail closed internally if metadata
    validation fails")."""


_stage1_session = None
_stage2_session = None
_ready = False
_init_attempted = False
# Human-readable reason the pipeline is NOT READY -- set at every `return False` branch in
# initialize() below, read by nsfw_image_classifier.classify_screenshot() so a screenshot that
# errors because of this (vs. a runtime classify() exception) gets a real reason attached instead
# of just "unavailable". Stays whatever it was last set to; only meaningful when _ready is False.
_not_ready_reason = "not initialized"


def _sha256(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _load_manifest() -> dict:
    if not os.path.isfile(MANIFEST_PATH):
        return {}
    try:
        with open(MANIFEST_PATH, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError) as error:
        log.warning("NSFW model manifest at %s is unreadable/invalid: %s", MANIFEST_PATH, error)
        return {}


def _verify_pin(path: str, manifest: dict, key: str) -> None:
    """No-ops if the manifest doesn't mention this model (pinning by hash is opt-in); raises if it
    does and the file on disk doesn't match -- an unauthorized/accidental model swap must never
    silently change moderation behavior."""
    entry = manifest.get(key)
    if not entry or not entry.get("sha256"):
        return
    actual = _sha256(path)
    expected = entry["sha256"]
    if actual.lower() != expected.lower():
        raise ModelValidationError(
            f"{key} model at {path} does not match manifest sha256 "
            f"(expected {expected[:12]}..., got {actual[:12]}...)"
        )


def validate_stage1_metadata(session) -> None:
    """Fails closed if the loaded ONNX graph's I/O doesn't match what this module's preprocessing/
    postprocessing assumes -- see module docstring point 2 under Stage 1."""
    inputs = session.get_inputs()
    outputs = session.get_outputs()
    if not inputs or not outputs:
        raise ModelValidationError("Stage 1 model exposes no input/output tensors")
    input_shape = inputs[0].shape
    # Dims may be symbolic (batch, dynamic axes) -- only check the axes we rely on being fixed.
    if len(input_shape) != 4:
        raise ModelValidationError(f"Stage 1 input must be rank-4 NCHW, got shape {input_shape}")
    channels = input_shape[1]
    if isinstance(channels, int) and channels != 3:
        raise ModelValidationError(f"Stage 1 input must have 3 channels, got {channels}")
    output_shape = outputs[0].shape
    if len(output_shape) != 2:
        raise ModelValidationError(f"Stage 1 output must be rank-2 (batch, num_labels), got {output_shape}")
    num_labels = output_shape[1]
    if isinstance(num_labels, int) and num_labels != STAGE1_EXPECTED_LABEL_COUNT:
        raise ModelValidationError(
            f"Stage 1 output must have exactly {STAGE1_EXPECTED_LABEL_COUNT} labels "
            f"(normal, nsfw), got {num_labels} -- NSFW_CLASSIFIER_NSFW_CLASS_INDEX would be "
            "meaningless against this artifact"
        )


def validate_stage2_metadata(session) -> None:
    inputs = session.get_inputs()
    outputs = session.get_outputs()
    if not inputs or not outputs:
        raise ModelValidationError("Stage 2 model exposes no input/output tensors")
    input_shape = inputs[0].shape
    if len(input_shape) != 4:
        raise ModelValidationError(f"Stage 2 input must be rank-4 NCHW, got shape {input_shape}")
    channels = input_shape[1]
    if isinstance(channels, int) and channels != 3:
        raise ModelValidationError(f"Stage 2 input must have 3 channels, got {channels}")
    output_shape = outputs[0].shape
    if len(output_shape) != 3:
        raise ModelValidationError(f"Stage 2 output must be rank-3 (batch, 4+classes, boxes), got {output_shape}")
    channel_dim = output_shape[1]
    expected = 4 + len(STAGE2_CLASS_NAMES)
    if isinstance(channel_dim, int) and channel_dim != expected:
        raise ModelValidationError(
            f"Stage 2 output channel dim is {channel_dim}, expected {expected} (4 box coords + "
            f"{len(STAGE2_CLASS_NAMES)} classes) -- STAGE2_CLASS_NAMES order/count doesn't match "
            "this artifact"
        )


def initialize() -> bool:
    """Loads both ONNX Runtime sessions once and validates them, per section 8.3's lifecycle.
    Call this once at process startup (see lockprofile_service.main()); classify()/available()
    also call it lazily on first use so ad-hoc scripts and tests don't need to remember to.
    Returns True iff the pipeline is READY (both stages loaded and validated); False otherwise --
    never raises, since "models not ready yet" must never crash the server or block startup -- it
    just leaves screenshot classification unavailable."""
    global _stage1_session, _stage2_session, _ready, _init_attempted, _not_ready_reason
    _init_attempted = True
    _ready = False

    if not ONNX_ENABLED:
        _not_ready_reason = "ONNX pipeline disabled via NSFW_ONNX_ENABLED"
        log.info(_not_ready_reason)
        return False
    if not (CLASSIFIER_ENABLED and DETECTOR_ENABLED):
        _not_ready_reason = (
            f"ONNX pipeline disabled: classifier_enabled={CLASSIFIER_ENABLED} "
            f"detector_enabled={DETECTOR_ENABLED} -- both stages are required (section 8.1)"
        )
        log.info(_not_ready_reason)
        return False

    try:
        import onnxruntime  # noqa: F401
        import numpy  # noqa: F401
        from PIL import Image  # noqa: F401
    except ImportError as error:
        _not_ready_reason = f"missing dependency: {error}"
        log.info("ONNX NSFW pipeline unavailable: %s", _not_ready_reason)
        return False

    if not os.path.isfile(CLASSIFIER_PATH):
        _not_ready_reason = f"no Stage 1 model at {CLASSIFIER_PATH}"
        log.info("ONNX NSFW pipeline not ready: %s", _not_ready_reason)
        return False
    if not os.path.isfile(DETECTOR_PATH):
        _not_ready_reason = f"no Stage 2 model at {DETECTOR_PATH}"
        log.info("ONNX NSFW pipeline not ready: %s", _not_ready_reason)
        return False

    import onnxruntime

    try:
        manifest = _load_manifest()
        _verify_pin(CLASSIFIER_PATH, manifest, "stage1")
        _verify_pin(DETECTOR_PATH, manifest, "stage2")

        stage1 = onnxruntime.InferenceSession(CLASSIFIER_PATH, providers=["CPUExecutionProvider"])
        validate_stage1_metadata(stage1)
        stage2 = onnxruntime.InferenceSession(DETECTOR_PATH, providers=["CPUExecutionProvider"])
        validate_stage2_metadata(stage2)
    except ModelValidationError as error:
        _not_ready_reason = f"metadata/manifest validation failed: {error}"
        log.error("ONNX NSFW pipeline failed metadata/manifest validation, staying NOT READY: %s", error)
        return False
    except Exception as error:
        _not_ready_reason = f"failed to load: {error}"
        log.error("ONNX NSFW pipeline failed to load, staying NOT READY: %s", error)
        return False

    _stage1_session = stage1
    _stage2_session = stage2
    _ready = True
    log.info(
        "ONNX NSFW pipeline READY: stage1=%s@%s stage2=%s@%s",
        CLASSIFIER_MODEL_NAME, CLASSIFIER_VERSION, DETECTOR_MODEL_NAME, DETECTOR_VERSION,
    )
    return True


def not_ready_reason() -> str:
    """Human-readable reason the pipeline is currently NOT READY -- see _not_ready_reason's
    comment. Meaningless (may be stale or the "not initialized" default) if available() is True;
    callers must check that first."""
    return _not_ready_reason


def available() -> bool:
    """True once `initialize()` has loaded and validated both models. Runs `initialize()` on first
    call if nothing has called it yet (e.g. under test, or a script that imports this module
    directly) -- production should call `initialize()` explicitly at startup so model loading never
    happens on a request thread (section 8.3/15.1)."""
    if not _init_attempted:
        return initialize()
    return _ready


def _tiles(width: int, height: int) -> list[tuple[int, int, int, int]]:
    """Overlapping tile bounds covering the full image, plus the whole image itself as one region.
    Capped at MAX_TILES total (section 6.3) -- if the raw grid would exceed it, later tiles in scan
    order are dropped rather than silently generating an unbounded number for pathological aspect
    ratios (e.g. a very tall scrolling-capture screenshot). The whole-image region is never dropped."""
    if max(width, height) <= TILE_MIN_IMAGE_DIMENSION:
        return [(0, 0, width, height)]

    stride = max(1, int(TILE_SIZE * (1 - TILE_OVERLAP_FRACTION)))
    bounds: list[tuple[int, int, int, int]] = [(0, 0, width, height)]
    y = 0
    while y < height:
        top = min(y, max(0, height - TILE_SIZE))
        bottom = min(height, top + TILE_SIZE)
        x = 0
        while x < width:
            if len(bounds) >= MAX_TILES:
                return bounds
            left = min(x, max(0, width - TILE_SIZE))
            right = min(width, left + TILE_SIZE)
            bounds.append((left, top, right, bottom))
            if right >= width:
                break
            x += stride
        if bottom >= height:
            break
        y += stride
    return bounds


def _stage1_score(image) -> float:
    import numpy as np

    resized = image.convert("RGB").resize((STAGE1_INPUT_SIZE, STAGE1_INPUT_SIZE))
    array = np.asarray(resized, dtype=np.float32) / 255.0
    array = (array - STAGE1_MEAN) / STAGE1_STD
    tensor = np.transpose(array, (2, 0, 1))[np.newaxis, ...]  # NCHW

    input_name = _stage1_session.get_inputs()[0].name
    logits = _stage1_session.run(None, {input_name: tensor})[0][0]
    # Numerically-stable softmax over the two logits, then take the NSFW-class probability.
    exp = np.exp(logits - np.max(logits))
    probabilities = exp / exp.sum()
    return float(probabilities[STAGE1_NSFW_CLASS_INDEX])


def _stage2_detect(image) -> list[dict]:
    import numpy as np
    from PIL import Image as PILImage

    width, height = image.size
    scale = STAGE2_INPUT_SIZE / max(width, height)
    resized_w, resized_h = max(1, round(width * scale)), max(1, round(height * scale))
    resized = image.convert("RGB").resize((resized_w, resized_h))

    padded = PILImage.new("RGB", (STAGE2_INPUT_SIZE, STAGE2_INPUT_SIZE), (114, 114, 114))
    padded.paste(resized, (0, 0))

    array = np.asarray(padded, dtype=np.float32) / 255.0
    tensor = np.transpose(array, (2, 0, 1))[np.newaxis, ...]

    input_name = _stage2_session.get_inputs()[0].name
    output = _stage2_session.run(None, {input_name: tensor})[0][0]  # (4 + num_classes, num_boxes)
    output = output.transpose(1, 0)  # (num_boxes, 4 + num_classes)

    boxes_xywh: list[tuple[float, float, float, float]] = []
    scores: list[float] = []
    labels: list[str] = []
    for row in output:
        class_scores = row[4:]
        class_index = int(np.argmax(class_scores))
        confidence = float(class_scores[class_index])
        if confidence < DETECTOR_SCORE_THRESHOLD:
            continue
        if class_index >= len(STAGE2_CLASS_NAMES):
            continue
        cx, cy, w, h = row[0], row[1], row[2], row[3]
        boxes_xywh.append((cx, cy, w, h))
        scores.append(confidence)
        labels.append(STAGE2_CLASS_NAMES[class_index])

    keep = _nms(boxes_xywh, scores, DETECTOR_NMS_THRESHOLD)
    detections: list[dict] = []
    for i in keep:
        cx, cy, w, h = boxes_xywh[i]
        left = (cx - w / 2) / scale
        top = (cy - h / 2) / scale
        right = (cx + w / 2) / scale
        bottom = (cy + h / 2) / scale
        detections.append({
            "label": labels[i],
            "confidence": scores[i],
            "bounds": (
                max(0, int(left)), max(0, int(top)),
                min(width, int(right)), min(height, int(bottom)),
            ),
        })
    return detections


def _nms(boxes_xywh: list[tuple[float, float, float, float]], scores: list[float], iou_threshold: float) -> list[int]:
    """Minimal greedy NMS -- no numpy vectorization needed at NudeNet's typical box counts."""
    def to_xyxy(box):
        cx, cy, w, h = box
        return (cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)

    def iou(a, b):
        ax1, ay1, ax2, ay2 = to_xyxy(a)
        bx1, by1, bx2, by2 = to_xyxy(b)
        ix1, iy1 = max(ax1, bx1), max(ay1, by1)
        ix2, iy2 = min(ax2, bx2), min(ay2, by2)
        inter = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
        area_a = max(0.0, ax2 - ax1) * max(0.0, ay2 - ay1)
        area_b = max(0.0, bx2 - bx1) * max(0.0, by2 - by1)
        union = area_a + area_b - inter
        return inter / union if union > 0 else 0.0

    order = sorted(range(len(scores)), key=lambda i: scores[i], reverse=True)
    keep: list[int] = []
    while order:
        current = order.pop(0)
        keep.append(current)
        order = [i for i in order if iou(boxes_xywh[current], boxes_xywh[i]) < iou_threshold]
    return keep


def _fuse(full_image_score: float, max_stage1: float, corroborating_regions: int, detections: list[dict]) -> str:
    """Explicit evidence-rule fusion (section 7) -- never an average of the two model scores.
    Mirrors the plan's section 7.2 worked examples exactly (see
    tests/test_onnx_nsfw_pipeline.py::FusionPolicyTests)."""
    strong_stage2 = any(
        d["label"] in STAGE2_EXPLICIT_CLASSES and d["confidence"] >= STAGE2_STRONG_THRESHOLD
        for d in detections
    )
    if strong_stage2:
        return DECISION_BLOCK

    if max_stage1 >= STAGE1_HIGH_THRESHOLD:
        # "Stage 1 very high AND supporting evidence exists" (section 7.1) -- corroboration can
        # come from the whole-image score agreeing, or from more than one region independently
        # spiking, not just a single isolated tile.
        corroborated = full_image_score >= STAGE2_GENERAL_THRESHOLD or corroborating_regions >= 2
        return DECISION_BLOCK if corroborated else DECISION_UNCERTAIN

    return DECISION_ALLOW


def classify(image_bytes: bytes) -> PipelineResult:
    """Runs the full two-stage pipeline. Raises PipelineUnavailableError if dependencies/model
    files/manifest validation aren't in order, or if inference itself fails -- callers must catch
    this and fall back to the existing classifier (never treat "pipeline broke" as either a BLOCK
    or an ALLOW verdict)."""
    if not available():
        raise PipelineUnavailableError("ONNX NSFW pipeline not available")

    from PIL import Image
    start = time.monotonic()
    try:
        image = Image.open(io.BytesIO(image_bytes))
        image.load()
    except Exception as error:
        raise PipelineUnavailableError(f"failed to decode image: {error}") from error

    try:
        width, height = image.size
        tile_bounds = _tiles(width, height)
        regions: list[RegionResult] = []
        full_image_score = 0.0
        max_stage1 = 0.0
        corroborating_regions = 0
        all_detections: list[dict] = []

        for tile_index, bounds in enumerate(tile_bounds):
            is_whole_image = bounds == (0, 0, width, height)
            crop = image if is_whole_image else image.crop(bounds)
            stage1_score = _stage1_score(crop)
            max_stage1 = max(max_stage1, stage1_score)
            if is_whole_image:
                full_image_score = stage1_score
            if stage1_score >= STAGE2_GENERAL_THRESHOLD:
                corroborating_regions += 1

            detections: list[dict] = []
            if stage1_score >= STAGE2_GENERAL_THRESHOLD:
                # Stage 2 escalation (section 6.2) -- only for ambiguous/high-risk regions, not
                # every tile, to keep the common (clearly-safe) case cheap.
                detections = _stage2_detect(crop)
                all_detections.extend(detections)

            regions.append(RegionResult(
                bounds=bounds, tile_index=tile_index, stage1_score=stage1_score,
                stage2_detections=detections,
            ))

        decision = _fuse(full_image_score, max_stage1, corroborating_regions, all_detections)

        return PipelineResult(
            decision=decision,
            nsfw_score=max_stage1,
            regions=regions,
            stage1_model_version=CLASSIFIER_VERSION,
            stage2_model_version=DETECTOR_VERSION,
            policy_version=POLICY_VERSION,
            processing_time_ms=int((time.monotonic() - start) * 1000),
            tile_count=len(tile_bounds),
            detection_count=len(all_detections),
        )
    except PipelineUnavailableError:
        raise
    except Exception as error:
        raise PipelineUnavailableError(f"inference failed: {error}") from error
