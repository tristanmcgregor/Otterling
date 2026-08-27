"""Covers the parts of onnx_nsfw_pipeline.py that don't need onnxruntime/numpy/Pillow or a real
model file: tiling coverage/overlap (section 5.2) and NMS. CI runs `unittest discover` with no
pip install step, so this file -- like onnx_nsfw_pipeline.py itself -- must not import those
third-party packages at module scope.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import onnx_nsfw_pipeline as pipeline


class AvailabilityTests(unittest.TestCase):
    def test_unavailable_without_model_file_or_deps(self):
        # In this test environment neither the ONNX deps nor a model file exist, so the pipeline
        # must report unavailable rather than raising -- this is the fallback contract
        # nsfw_image_classifier.classify_screenshot depends on.
        self.assertFalse(pipeline.available())

    def test_classify_raises_unavailable_rather_than_a_verdict(self):
        with self.assertRaises(pipeline.PipelineUnavailableError):
            pipeline.classify(b"not a real image")


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
        # Section 5.2: tiles must overlap so content on a boundary isn't split across two weak
        # crops. Find two tiles in the same row and assert their horizontal ranges intersect.
        width, height = 2000, 500
        bounds = [b for b in pipeline._tiles(width, height) if b != (0, 0, width, height)]
        row = [b for b in bounds if b[1] == 0]
        row.sort(key=lambda b: b[0])
        self.assertGreaterEqual(len(row), 2)
        first, second = row[0], row[1]
        self.assertLess(second[0], first[2])  # second tile starts before first tile ends


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


if __name__ == "__main__":
    unittest.main()
