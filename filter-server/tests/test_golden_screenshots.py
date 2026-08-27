"""Golden integration set (migration plan section 14.3): verifies the *wiring* of whatever
pretrained models are actually installed -- not model quality, not a training/validation split.

This is NOT a training dataset and must never become one. It also must never be committed:
labeled NSFW/borderline imagery has no business in git history, so tests/golden_images/ is
gitignored and this file self-skips whenever it's absent or empty. Populate it locally (or on a
CI runner with the real models mounted) before relying on this file for anything.

Expected layout -- one subdirectory per label, images directly inside:
    tests/golden_images/
      safe/            normal portraits, fashion, swimwear, classical art, medical-style images,
                        anime/illustration, meme/UI-heavy screenshots, social-feed screenshots,
                        browser screenshots -- anything section 14.3 lists as a hard negative
      nsfw/             explicit images, including small-region-in-screenshot cases

Run manually after populating the directory and dropping in real models:
    python3 -m unittest tests.test_golden_screenshots -v
"""
import glob
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import onnx_nsfw_pipeline as pipeline

_GOLDEN_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "golden_images")
_IMAGE_GLOB = "*.png", "*.jpg", "*.jpeg", "*.webp"


def _images(label: str) -> list[str]:
    directory = os.path.join(_GOLDEN_DIR, label)
    if not os.path.isdir(directory):
        return []
    paths: list[str] = []
    for pattern in _IMAGE_GLOB:
        paths.extend(glob.glob(os.path.join(directory, pattern)))
    return sorted(paths)


_SAFE_IMAGES = _images("safe")
_NSFW_IMAGES = _images("nsfw")
_HAVE_GOLDEN_SET = bool(_SAFE_IMAGES or _NSFW_IMAGES)


@unittest.skipUnless(_HAVE_GOLDEN_SET, "tests/golden_images/ not populated -- see this file's docstring")
class GoldenScreenshotTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not pipeline.available():
            raise unittest.SkipTest("ONNX pipeline not available -- no models installed")

    def test_safe_images_are_not_blocked(self):
        failures = []
        for path in _SAFE_IMAGES:
            with open(path, "rb") as fh:
                result = pipeline.classify(fh.read())
            if result.decision != pipeline.DECISION_ALLOW:
                failures.append((os.path.basename(path), result.decision, result.nsfw_score))
        self.assertEqual(failures, [], f"false positives: {failures}")

    def test_nsfw_images_are_blocked(self):
        failures = []
        for path in _NSFW_IMAGES:
            with open(path, "rb") as fh:
                result = pipeline.classify(fh.read())
            if result.decision == pipeline.DECISION_ALLOW:
                failures.append((os.path.basename(path), result.decision, result.nsfw_score))
        self.assertEqual(failures, [], f"false negatives: {failures}")


if __name__ == "__main__":
    unittest.main()
