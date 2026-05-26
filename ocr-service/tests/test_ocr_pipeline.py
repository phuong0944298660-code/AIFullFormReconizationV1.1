import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.ocr_pipeline import extract_text_from_rec_response, recognize_crop, recognize_crops


class FakeModelClient:
    def recognize(self, image_bytes):
        return "AGUIAR", 0.91


class OcrPipelineTest(unittest.TestCase):
    def test_extracts_text_and_confidence_from_nested_paddle_response(self):
        text, confidence = extract_text_from_rec_response(
            [{"res": {"rec_text": "AGUIAR", "rec_score": 0.91}}]
        )

        self.assertEqual(text, "AGUIAR")
        self.assertEqual(confidence, 0.91)

    def test_recognize_crop_uses_injected_model_client(self):
        result = recognize_crop(b"fake-image", model_client=FakeModelClient())

        self.assertEqual(result["text"], "AGUIAR")
        self.assertEqual(result["confidence"], 0.91)
        self.assertEqual(result["status"], "available")

    def test_recognize_crops_returns_one_result_per_crop(self):
        result = recognize_crops([b"first", b"second"], model_client=FakeModelClient())

        self.assertEqual(len(result["results"]), 2)
        self.assertEqual(result["results"][0]["text"], "AGUIAR")
        self.assertEqual(result["results"][1]["confidence"], 0.91)


if __name__ == "__main__":
    unittest.main()
