"""Covers the parts of onnx_nsfw_pipeline.py that don't need onnxruntime/numpy/Pillow or a real
model file: tiling coverage/overlap/cap, NMS, the fusion policy, and the fallback contract. CI runs
`unittest discover` with no pip install step, so this file -- like onnx_nsfw_pipeline.py itself --
must not import those third-party packages at module scope.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import onnx_nsfw_pipeline as pipeline


class AvailabilityTests(unittest.TestCase):
    def test_unavailable_without_model_files_or_deps(self):
        # In this test environment neither the ONNX deps nor model files exist, so the pipeline
        # must report unavailable rather than raising -- this is the fallback contract
        # nsfw_image_classifier.classify_screenshot depends on.
        self.assertFalse(pipeline.available())

    def test_initialize_returns_false_rather_than_raising(self):
        self.assertFalse(pipeline.initialize())

    def test_classify_raises_unavailable_rather_than_a_verdict(self):
        with self.assertRaises(pipeline.PipelineUnavailableError):
            pipeline.classify(b"not a real image")

    def test_both_stages_required_even_if_one_were_somehow_loadable(self):
        # Section 8.1: a single-stage-only deployment must never report READY. Disabling the
        # detector alone must be enough to force NOT READY regardless of the classifier's state.
        old = pipeline.DETECTOR_ENABLED
        pipeline.DETECTOR_ENABLED = False
        try:
            self.assertFalse(pipeline.initialize())
        finally:
            pipeline.DETECTOR_ENABLED = old


class TilingTests(unittest.TestCase):
    def test_small_image_is_a_single_whole_tile(self):
        bounds = pipeline._tiles(200, 200)
        self.assertEqual(bounds, [(0, 0, 200, 200)])

    def test_large_image_includes_the_whole_frame_plus_tiles(self):
        width, height = 1080, 2400
        bounds = pipeline._tiles(width, height)
        self.assertIn((0, 0, width, height), bounds)
        self.assertGreater(len(bounds), 1)

    def test_tiles_cover_every_edge_and_corner(self):
        width, height = 1080, 2400
        bounds = pipeline._tiles(width, height)
        rights = [b[2] for b in bounds]
        bottoms = [b[3] for b in bounds]
        lefts = [b[0] for b in bounds]
        tops = [b[1] for b in bounds]
        self.assertIn(width, rights)
        self.assertIn(height, bottoms)
        self.assertEqual(min(lefts), 0)
        self.assertEqual(min(tops), 0)

    def test_consecutive_tiles_overlap(self):
        # Section 6: tiles must overlap so content on a boundary isn't split across two weak
        # crops. Find two tiles in the same row and assert their horizontal ranges intersect.
        width, height = 2000, 500
        bounds = [b for b in pipeline._tiles(width, height) if b != (0, 0, width, height)]
        row = [b for b in bounds if b[1] == 0]
        row.sort(key=lambda b: b[0])
        self.assertGreaterEqual(len(row), 2)
        first, second = row[0], row[1]
        self.assertLess(second[0], first[2])  # second tile starts before first tile ends

    def test_tile_count_is_bounded_for_a_pathological_aspect_ratio(self):
        # Section 6.3: a very tall scrolling-capture screenshot must not generate hundreds of tiles.
        bounds = pipeline._tiles(1080, 40000)
        self.assertLessEqual(len(bounds), pipeline.MAX_TILES)
        self.assertIn((0, 0, 1080, 40000), bounds)  # whole-image region is never dropped


class NmsTests(unittest.TestCase):
    def test_suppresses_overlapping_lower_confidence_box(self):
        boxes = [(10, 10, 20, 20), (11, 11, 20, 20), (200, 200, 20, 20)]
        scores = [0.9, 0.8, 0.7]
        keep = pipeline._nms(boxes, scores, iou_threshold=0.45)
        self.assertEqual(keep, [0, 2])

    def test_keeps_non_overlapping_boxes(self):
        boxes = [(10, 10, 20, 20), (500, 500, 20, 20)]
        scores = [0.9, 0.9]
        keep = pipeline._nms(boxes, scores, iou_threshold=0.45)
        self.assertEqual(sorted(keep), [0, 1])


def _detection(label, confidence):
    return {"label": label, "confidence": confidence, "bounds": (0, 0, 10, 10)}


class FusionPolicyTests(unittest.TestCase):
    """Mirrors the migration plan's section 7.2 worked examples exactly."""

    def test_strong_stage2_blocks_even_with_very_high_stage1(self):
        # Stage 1 = 0.99, Stage 2 = genitalia exposed @ 0.92 => BLOCK
        decision = pipeline._fuse(
            full_image_score=0.99, max_stage1=0.99, corroborating_regions=1,
            detections=[_detection("FEMALE_GENITALIA_EXPOSED", 0.92)],
        )
        self.assertEqual(decision, pipeline.DECISION_BLOCK)

    def test_high_stage1_alone_with_corroboration_blocks(self):
        # Stage 1 = 0.97, no Stage 2 detections, but the whole-image score itself is high
        # (corroborating evidence) => BLOCK.
        decision = pipeline._fuse(
            full_image_score=0.97, max_stage1=0.97, corroborating_regions=1, detections=[],
        )
        self.assertEqual(decision, pipeline.DECISION_BLOCK)

    def test_high_stage1_alone_without_corroboration_is_uncertain(self):
        # Stage 1 = 0.97 on a single isolated tile, whole image and every other region low,
        # nothing from Stage 2 => UNCERTAIN, not an automatic BLOCK (section 7.3).
        decision = pipeline._fuse(
            full_image_score=0.10, max_stage1=0.97, corroborating_regions=1, detections=[],
        )
        self.assertEqual(decision, pipeline.DECISION_UNCERTAIN)

    def test_strong_stage2_blocks_despite_modest_stage1(self):
        # Stage 1 = 0.60, Stage 2 = female breast exposed @ 0.90 => BLOCK
        decision = pipeline._fuse(
            full_image_score=0.60, max_stage1=0.60, corroborating_regions=1,
            detections=[_detection("FEMALE_BREAST_EXPOSED", 0.90)],
        )
        self.assertEqual(decision, pipeline.DECISION_BLOCK)

    def test_weak_class_detection_does_not_block(self):
        # Stage 1 = 0.55, Stage 2 = face @ 0.95 => ALLOW (face is never explicit evidence)
        decision = pipeline._fuse(
            full_image_score=0.55, max_stage1=0.55, corroborating_regions=1,
            detections=[_detection("FACE_FEMALE", 0.95)],
        )
        self.assertEqual(decision, pipeline.DECISION_ALLOW)

    def test_low_stage1_with_weak_evidence_allows(self):
        # Stage 1 = 0.45, Stage 2 = belly exposed @ 0.80 => ALLOW
        decision = pipeline._fuse(
            full_image_score=0.45, max_stage1=0.45, corroborating_regions=1,
            detections=[_detection("BELLY_EXPOSED", 0.80)],
        )
        self.assertEqual(decision, pipeline.DECISION_ALLOW)

    def test_never_averages_scores(self):
        # A high Stage 2 score on a *weak* class plus a low Stage 1 score must not combine into a
        # blocking "average" -- fusion is rule-based, not arithmetic.
        decision = pipeline._fuse(
            full_image_score=0.20, max_stage1=0.20, corroborating_regions=0,
            detections=[_detection("FEET_EXPOSED", 0.99)],
        )
        self.assertEqual(decision, pipeline.DECISION_ALLOW)


if __name__ == "__main__":
    unittest.main()
