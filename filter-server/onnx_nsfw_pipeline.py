"""On-device-grade two-stage NSFW pipeline, run server-side.

Implements the architecture from the NSFW Screenshot Classifier Integration Plan (see
`filter-server/nsfw_image_classifier.py`'s docstring for the sibling Claude-vision path this
supplements): a fast Stage 1 binary classifier (Falconsai-family ViT) over the full screenshot
plus overlapping tiles, escalating ambiguous/high-risk regions to a Stage 2 nudity object detector
(NudeNet-family YOLO), fused by an explicit policy rather than an averaged score.

Deliberately server-side, not on-device: the guardian/product decision (see AGENTS.md history and
the plan doc) was to keep screenshot classification off the phone entirely to save battery -- the
screenshot is already uploaded to `/screenshot-classify` (ScreenshotUploader.kt) for the existing
Claude-vision classifier, so this module just gives that route a second, cheaper/faster classifier
to try first. Torch/HF are not required at runtime -- only `onnxruntime`, `numpy`, and `Pillow`,
which is what actually runs in production; conversion from the original PyTorch checkpoints to
ONNX is a one-time offline step (see README section below), not something this module does.

MODEL FILES ARE NOT BUNDLED. This module is a pluggable scaffold: it loads whatever `.onnx` files
are dropped at NSFW_STAGE1_MODEL_PATH / NSFW_STAGE2_MODEL_PATH and no-ops (returns unavailable)
until they exist. See "Bringing your own models" below for exactly what to drop in. Until then,
`/screenshot-classify` keeps working exactly as before, via nsfw_image_classifier.py's Claude-vision
fallback -- this is additive, not a replacement, until the operator opts in by providing model
files.

Bringing your own models
-------------------------
Stage 1 (binary classifier, e.g. Falconsai/nsfw_image_detection or nsfw_image_detection_26):
  1. Export the HF ViTForImageClassification checkpoint to ONNX (e.g. via `optimum-cli export onnx`
     or `torch.onnx.export`) with a single float32 NCHW input, shape (1, 3, 224, 224), and a
     (1, 2) logits output.
  2. Confirm the label order: this module assumes id2label {0: "normal"/"safe", 1: "nsfw"} (which
     matches both public Falconsai model cards as of writing) -- verify against the specific
     checkpoint's config.json before trusting it, per the plan's section 5.3 ("do not invent
     preprocessing/label parameters").
  3. Confirm image_processor preprocessing (resize/crop/mean/std) against that checkpoint's own
     preprocessor_config.json. STAGE1_INPUT_SIZE/MEAN/STD below default to the values published on
     the original Falconsai/nsfw_image_detection card (224x224, mean=std=0.5) -- override via env
     if a different checkpoint's config differs.
  4. Drop the .onnx file at NSFW_STAGE1_MODEL_PATH and set NSFW_STAGE1_MODEL_VERSION to a
     content hash or the HF commit SHA you exported from (see [12] Model and Policy Versioning).

Stage 2 (nudity object detector, e.g. NudeNet 320n/640m, YOLOv8-derived):
  1. Export/obtain the ONNX graph with a single float32 NCHW input (1, 3, N, N) where N is
     NSFW_STAGE2_INPUT_SIZE (320 or 640), and the standard YOLOv8 detection head output
     (1, 4 + num_classes, num_boxes).
  2. Confirm STAGE2_CLASS_NAMES below matches the exported model's class order exactly -- these
     are NudeNet's published 320n/640m label order; a different checkpoint may differ.
  3. Drop the .onnx file at NSFW_STAGE2_MODEL_PATH and set NSFW_STAGE2_MODEL_VERSION.

Neither step above -- picking a real threshold, or deciding this pipeline beats the existing
Claude-vision classifier in production -- is something this file can do for you. Section 7/8 of the
plan (build a locked, representative screenshot benchmark and calibrate NSFW_HIGH_RISK_THRESHOLD /
NSFW_REVIEW_THRESHOLD / NSFW_STAGE2_CONFIDENCE_THRESHOLD against it) is a required manual step
before relying on this path in production; the defaults below are placeholders, not calibrated
values, exactly as section 7 warns against.
"""
from __future__ import annotations

import io
import logging
import os
import time
from dataclasses import dataclass, field

log = logging.getLogger("otterling.onnx_nsfw_pipeline")

# --- Model files & versioning (section 12: pin exact revision/hash, never "latest") -----------
STAGE1_MODEL_PATH = os.environ.get("NSFW_STAGE1_MODEL_PATH", "/opt/otterling/models/nsfw_stage1.onnx")
STAGE1_MODEL_VERSION = os.environ.get("NSFW_STAGE1_MODEL_VERSION", "unpinned")
STAGE2_MODEL_PATH = os.environ.get("NSFW_STAGE2_MODEL_PATH", "/opt/otterling/models/nsfw_stage2_nudenet.onnx")
STAGE2_MODEL_VERSION = os.environ.get("NSFW_STAGE2_MODEL_VERSION", "unpinned")
# Policy version travels with the model versions in every logged/retained decision (section 12) so
# a threshold-only change is distinguishable from a model swap after the fact.
POLICY_VERSION = os.environ.get("NSFW_POLICY_VERSION", "v1")

# --- Stage 1 preprocessing (section 5.3 -- must match the exported checkpoint's own config) ----
STAGE1_INPUT_SIZE = int(os.environ.get("NSFW_STAGE1_INPUT_SIZE", "224"))
STAGE1_MEAN = float(os.environ.get("NSFW_STAGE1_MEAN", "0.5"))
STAGE1_STD = float(os.environ.get("NSFW_STAGE1_STD", "0.5"))
# Index of the "nsfw" class in the model's logits -- flip via env if a checkpoint's id2label differs.
STAGE1_NSFW_CLASS_INDEX = int(os.environ.get("NSFW_STAGE1_NSFW_CLASS_INDEX", "1"))

# --- Stage 2 preprocessing / classes -------------------------------------------------------------
STAGE2_INPUT_SIZE = int(os.environ.get("NSFW_STAGE2_INPUT_SIZE", "320"))
# Published NudeNet 320n/640m class order. Verify against the specific exported checkpoint.
STAGE2_CLASS_NAMES = [
    "FEMALE_GENITALIA_COVERED", "FACE_FEMALE", "BUTTOCKS_EXPOSED", "FEMALE_BREAST_EXPOSED",
    "FEMALE_GENITALIA_EXPOSED", "MALE_BREAST_EXPOSED", "ANUS_EXPOSED", "FEET_EXPOSED",
    "BELLY_COVERED", "FEET_COVERED", "ARMPITS_COVERED", "ARMPITS_EXPOSED", "FACE_MALE",
    "BELLY_EXPOSED", "MALE_GENITALIA_EXPOSED", "ANUS_COVERED", "FEMALE_BREAST_COVERED",
    "BUTTOCKS_COVERED",
]
# Subset of the classes above that count as "strong explicit detection" for fusion (section 6.3).
# Deliberately excludes plain FACE_*/COVERED/swimwear-adjacent labels -- those are the hard
# negatives section 8 calls out (swimwear, lingerie, shirtless) and should not alone trigger BLOCK.
STAGE2_EXPLICIT_CLASSES = frozenset({
    "FEMALE_GENITALIA_EXPOSED", "MALE_GENITALIA_EXPOSED", "ANUS_EXPOSED",
    "FEMALE_BREAST_EXPOSED", "BUTTOCKS_EXPOSED",
})
STAGE2_CONFIDENCE_THRESHOLD = float(os.environ.get("NSFW_STAGE2_CONFIDENCE_THRESHOLD", "0.6"))
STAGE2_IOU_THRESHOLD = float(os.environ.get("NSFW_STAGE2_IOU_THRESHOLD", "0.45"))

# --- Region extraction (section 5.2) -------------------------------------------------------------
TILE_SIZE = int(os.environ.get("NSFW_TILE_SIZE", "448"))
# Fraction of a tile's size that consecutive tiles overlap by, so explicit content sitting on a
# tile boundary is never split across two weak inputs (section 5.2).
TILE_OVERLAP_FRACTION = float(os.environ.get("NSFW_TILE_OVERLAP_FRACTION", "0.25"))
# Screenshots at or below this on-screen size are classified whole (single "tile") -- tiling a
# small image just re-crops the same content into overlapping near-duplicates for no benefit.
TILE_MIN_IMAGE_DIMENSION = int(os.environ.get("NSFW_TILE_MIN_IMAGE_DIMENSION", TILE_SIZE * 2))

# --- Score fusion thresholds (section 6.3 / 7 -- PLACEHOLDERS, calibrate against section 8's
# locked benchmark before production use) ---------------------------------------------------------
HIGH_RISK_THRESHOLD = float(os.environ.get("NSFW_HIGH_RISK_THRESHOLD", "0.9"))
REVIEW_THRESHOLD = float(os.environ.get("NSFW_REVIEW_THRESHOLD", "0.5"))

DECISION_ALLOW = "ALLOW"
DECISION_BLOCK = "BLOCK"


@dataclass
class RegionResult:
    bounds: tuple[int, int, int, int]  # (left, top, right, bottom) in original-image pixels
    stage1_score: float
    stage2_detections: list[dict] = field(default_factory=list)


@dataclass
class PipelineResult:
    decision: str  # DECISION_ALLOW | DECISION_BLOCK
    nsfw_score: float
    regions: list[RegionResult]
    stage1_model_version: str
    stage2_model_version: str | None
    policy_version: str
    processing_time_ms: int


class PipelineUnavailableError(Exception):
    """Raised when onnxruntime/numpy/Pillow or the model files aren't present -- callers must
    treat this as "try the fallback classifier", never as a classification verdict."""


_stage1_session = None
_stage2_session = None
_availability_checked = False
_available = False


def available() -> bool:
    """True once both dependencies and both model files are present. Cached after the first
    check (env/files aren't expected to change while the process is running) -- restart the
    process after dropping in model files."""
    global _availability_checked, _available
    if _availability_checked:
        return _available
    _availability_checked = True
    try:
        import numpy  # noqa: F401
        import onnxruntime  # noqa: F401
        from PIL import Image  # noqa: F401
    except ImportError as error:
        log.info("ONNX NSFW pipeline unavailable: missing dependency (%s)", error)
        _available = False
        return False
    if not os.path.isfile(STAGE1_MODEL_PATH):
        log.info("ONNX NSFW pipeline unavailable: no Stage 1 model at %s", STAGE1_MODEL_PATH)
        _available = False
        return False
    # Stage 2 is optional: a deployment may run Stage 1-only (accepting no escalation evidence)
    # while a NudeNet conversion is still pending -- fusion below degrades to stage1-threshold-only.
    _available = True
    return True


def _stage1() -> "onnxruntime.InferenceSession":
    global _stage1_session
    if _stage1_session is None:
        import onnxruntime
        _stage1_session = onnxruntime.InferenceSession(
            STAGE1_MODEL_PATH, providers=["CPUExecutionProvider"],
        )
    return _stage1_session


def _stage2():
    global _stage2_session
    if _stage2_session is None and os.path.isfile(STAGE2_MODEL_PATH):
        import onnxruntime
        _stage2_session = onnxruntime.InferenceSession(
            STAGE2_MODEL_PATH, providers=["CPUExecutionProvider"],
        )
    return _stage2_session


def _tiles(width: int, height: int) -> list[tuple[int, int, int, int]]:
    """Overlapping tile bounds covering the full image, plus the whole image itself as one region
    (section 6.1 -- full screenshot is run "as a contextual signal" alongside crops)."""
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

    session = _stage1()
    input_name = session.get_inputs()[0].name
    logits = session.run(None, {input_name: tensor})[0][0]
    # Numerically-stable softmax over the two logits, then take the NSFW-class probability.
    exp = np.exp(logits - np.max(logits))
    probabilities = exp / exp.sum()
    return float(probabilities[STAGE1_NSFW_CLASS_INDEX])


def _stage2_detect(image) -> list[dict]:
    import numpy as np

    session = _stage2()
    if session is None:
        return []

    width, height = image.size
    scale = STAGE2_INPUT_SIZE / max(width, height)
    resized_w, resized_h = max(1, round(width * scale)), max(1, round(height * scale))
    resized = image.convert("RGB").resize((resized_w, resized_h))

    from PIL import Image as PILImage
    padded = PILImage.new("RGB", (STAGE2_INPUT_SIZE, STAGE2_INPUT_SIZE), (114, 114, 114))
    padded.paste(resized, (0, 0))

    array = np.asarray(padded, dtype=np.float32) / 255.0
    tensor = np.transpose(array, (2, 0, 1))[np.newaxis, ...]

    input_name = session.get_inputs()[0].name
    output = session.run(None, {input_name: tensor})[0][0]  # (4 + num_classes, num_boxes)
    output = output.transpose(1, 0)  # (num_boxes, 4 + num_classes)

    detections: list[dict] = []
    boxes_xywh: list[tuple[float, float, float, float]] = []
    scores: list[float] = []
    labels: list[str] = []
    for row in output:
        class_scores = row[4:]
        class_index = int(np.argmax(class_scores))
        confidence = float(class_scores[class_index])
        if confidence < STAGE2_CONFIDENCE_THRESHOLD:
            continue
        if class_index >= len(STAGE2_CLASS_NAMES):
            continue
        cx, cy, w, h = row[0], row[1], row[2], row[3]
        boxes_xywh.append((cx, cy, w, h))
        scores.append(confidence)
        labels.append(STAGE2_CLASS_NAMES[class_index])

    keep = _nms(boxes_xywh, scores, STAGE2_IOU_THRESHOLD)
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


def classify(image_bytes: bytes) -> PipelineResult:
    """Runs the full two-stage pipeline. Raises PipelineUnavailableError if dependencies/model
    files are missing, or if inference itself fails -- callers must catch this and fall back to
    the existing classifier (never treat "pipeline broke" as either a BLOCK or an ALLOW verdict).
    """
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
        regions: list[RegionResult] = []
        best_score = 0.0
        strong_explicit = False

        for bounds in _tiles(width, height):
            crop = image if bounds == (0, 0, width, height) else image.crop(bounds)
            stage1_score = _stage1_score(crop)
            best_score = max(best_score, stage1_score)

            detections: list[dict] = []
            if stage1_score >= REVIEW_THRESHOLD:
                # Stage 2 escalation (section 6.2) -- only for ambiguous/high-risk regions, not
                # every tile, to keep the common (clearly-safe) case cheap.
                detections = _stage2_detect(crop)
                if any(d["label"] in STAGE2_EXPLICIT_CLASSES and d["confidence"] >= STAGE2_CONFIDENCE_THRESHOLD
                       for d in detections):
                    strong_explicit = True

            regions.append(RegionResult(bounds=bounds, stage1_score=stage1_score, stage2_detections=detections))

        # Fusion policy (section 6.3) -- explicit rules, not an averaged score.
        if strong_explicit or best_score >= HIGH_RISK_THRESHOLD:
            decision = DECISION_BLOCK
        else:
            decision = DECISION_ALLOW

        return PipelineResult(
            decision=decision,
            nsfw_score=best_score,
            regions=regions,
            stage1_model_version=STAGE1_MODEL_VERSION,
            stage2_model_version=STAGE2_MODEL_VERSION if _stage2() is not None else None,
            policy_version=POLICY_VERSION,
            processing_time_ms=int((time.monotonic() - start) * 1000),
        )
    except PipelineUnavailableError:
        raise
    except Exception as error:
        raise PipelineUnavailableError(f"inference failed: {error}") from error
