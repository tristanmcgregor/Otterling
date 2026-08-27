"""Model-contract tests (migration plan section 14.2): fail loudly if a model artifact's actual
input/output shape or label count doesn't match what onnx_nsfw_pipeline.py assumes.

These only run against real .onnx files and a real onnxruntime install, neither of which exists in
this dev/CI environment (CI runs `unittest discover` with no pip install step, matching every other
file here) -- they self-skip rather than fail when the dependency or model files are absent, so
this file does not need special CI wiring to be inert everywhere except a deployment that actually
has models installed. Run manually after dropping artifacts into ./models/ with:
    python3 -m unittest tests.test_model_contract -v
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import onnx_nsfw_pipeline as pipeline

try:
    import onnxruntime
    _HAVE_ORT = True
except ImportError:
    _HAVE_ORT = False

_HAVE_STAGE1 = os.path.isfile(pipeline.CLASSIFIER_PATH)
_HAVE_STAGE2 = os.path.isfile(pipeline.DETECTOR_PATH)


@unittest.skipUnless(_HAVE_ORT and _HAVE_STAGE1, "no onnxruntime install or no Stage 1 model file")
class Stage1ContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.session = onnxruntime.InferenceSession(
            pipeline.CLASSIFIER_PATH, providers=["CPUExecutionProvider"],
        )

    def test_metadata_validates(self):
        # Raises ModelValidationError (a PipelineUnavailableError) if the actual artifact doesn't
        # match this module's NCHW / 2-label assumptions -- see the plan's "do not silently accept
        # changed class order or preprocessing" (section 17).
        pipeline.validate_stage1_metadata(self.session)

    def test_inference_succeeds_on_a_known_image(self):
        from PIL import Image
        image = Image.new("RGB", (pipeline.STAGE1_INPUT_SIZE, pipeline.STAGE1_INPUT_SIZE), (128, 128, 128))
        score = pipeline._stage1_score(image)
        self.assertGreaterEqual(score, 0.0)
        self.assertLessEqual(score, 1.0)


@unittest.skipUnless(_HAVE_ORT and _HAVE_STAGE2, "no onnxruntime install or no Stage 2 model file")
class Stage2ContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.session = onnxruntime.InferenceSession(
            pipeline.DETECTOR_PATH, providers=["CPUExecutionProvider"],
        )

    def test_metadata_validates(self):
        pipeline.validate_stage2_metadata(self.session)

    def test_detector_label_count_matches_expected_metadata(self):
        output_shape = self.session.get_outputs()[0].shape
        expected = 4 + len(pipeline.STAGE2_CLASS_NAMES)
        self.assertEqual(output_shape[1], expected)

    def test_inference_succeeds_on_a_known_image(self):
        from PIL import Image
        image = Image.new("RGB", (pipeline.STAGE2_INPUT_SIZE, pipeline.STAGE2_INPUT_SIZE), (128, 128, 128))
        detections = pipeline._stage2_detect(image)
        self.assertIsInstance(detections, list)


@unittest.skipUnless(_HAVE_ORT and _HAVE_STAGE1 and _HAVE_STAGE2, "full pipeline models not installed")
class PipelineReadyTests(unittest.TestCase):
    def test_pipeline_becomes_ready_with_both_models_present(self):
        self.assertTrue(pipeline.initialize())


if __name__ == "__main__":
    unittest.main()
