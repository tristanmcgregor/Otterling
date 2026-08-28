"""Screenshot NSFW classifier for POST /screenshot-classify (see lockprofile_service.py).

Runs the on-device-grade two-stage ONNX pipeline (`onnx_nsfw_pipeline` -- the two-stage
Falconsai/NudeNet pipeline, per the NSFW Pretrained Model Integration Migration Plan) -- see that
module's docstring for why this stays server-side rather than moving to the phone (battery). The
earlier `claude -p` vision fallback was removed once the ONNX pipeline had real model files
configured and confirmed working; this classifier now depends entirely on that pipeline being
available.
"""
from __future__ import annotations

import json
import logging

import onnx_nsfw_pipeline

log = logging.getLogger("otterling.nsfw_image_classifier")

# Escalates repeated ONNX pipeline failures from warning to error (section 8.2: "do not silently
# swallow repeated deterministic configuration/model errors forever").
_REPEATED_FAILURE_LOG_THRESHOLD = 20
_consecutive_pipeline_failures = 0


def classify_screenshot(image_bytes: bytes) -> bool | None:
    """Returns True if the screenshot is NSFW (should block + alert), False if safe, None if the
    pipeline itself is unavailable or classification failed -- callers must fail *open* on None
    (discard the image, don't block, don't alert) so an outage of this classifier can never itself
    count as a false positive, matching ai_classifier.classify_with_ai's contract.

    An internal UNCERTAIN pipeline verdict (section 7.3 -- e.g. one very-high-confidence tile with
    no corroborating region or Stage 2 evidence) resolves to a boolean via
    onnx_nsfw_pipeline.UNCERTAIN_POLICY -- the pipeline already ran successfully and produced real
    evidence, so this never throws that evidence away."""
    global _consecutive_pipeline_failures
    if not onnx_nsfw_pipeline.available():
        log.warning("ONNX pipeline not available; screenshot classification skipped")
        return None

    try:
        result = onnx_nsfw_pipeline.classify(image_bytes)
        _consecutive_pipeline_failures = 0
        log.info(
            "ONNX pipeline decision=%s confidence=%.3f tiles=%d detections=%d "
            "stage1_version=%s stage2_version=%s policy=%s time_ms=%d",
            result.decision, result.nsfw_score, result.tile_count, result.detection_count,
            result.stage1_model_version, result.stage2_model_version, result.policy_version,
            result.processing_time_ms,
        )
        log.debug("ONNX pipeline diagnostics: %s", json.dumps(result.diagnostics()))
        if result.decision == onnx_nsfw_pipeline.DECISION_BLOCK:
            return True
        if result.decision == onnx_nsfw_pipeline.DECISION_ALLOW:
            return False
        # UNCERTAIN
        resolved = onnx_nsfw_pipeline.UNCERTAIN_POLICY == "block"
        log.info("ONNX pipeline UNCERTAIN, resolved via NSFW_UNCERTAIN_POLICY=%s -> %s",
                  onnx_nsfw_pipeline.UNCERTAIN_POLICY, resolved)
        return resolved
    except onnx_nsfw_pipeline.PipelineUnavailableError as error:
        _consecutive_pipeline_failures += 1
        log_fn = (log.error if _consecutive_pipeline_failures % _REPEATED_FAILURE_LOG_THRESHOLD == 0
                  else log.warning)
        log_fn("ONNX pipeline failed (%d consecutive): %s", _consecutive_pipeline_failures, error)
        return None
