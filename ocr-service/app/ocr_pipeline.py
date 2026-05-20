from __future__ import annotations

import io
import os
from pathlib import Path
from typing import Any

PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CACHE_DIR = PROJECT_ROOT / ".paddlex_cache"
DEFAULT_REC_MODEL_DIR = PROJECT_ROOT / "models" / "PP-OCRv5_server_rec_infer"


def configure_paddlex_environment() -> None:
    os.environ.setdefault("PADDLE_PDX_CACHE_HOME", str(DEFAULT_CACHE_DIR))


configure_paddlex_environment()


class LocalTextRecognitionClient:
    def __init__(
        self,
        *,
        rec_model_dir: str,
        device: str = "cpu",
        cpu_threads: int = 4,
    ) -> None:
        self.rec_model_dir = rec_model_dir
        self.device = device
        self.cpu_threads = cpu_threads
        self._recognizer = None

    def recognize(self, image_bytes: bytes) -> tuple[str, float]:
        response = self._recognizer_model().predict(image_bytes_to_array(image_bytes))
        return extract_text_from_rec_response(response)

    def _recognizer_model(self) -> Any:
        if self._recognizer is None:
            from paddleocr import TextRecognition

            self._recognizer = TextRecognition(
                model_dir=self.rec_model_dir,
                device=self.device,
                cpu_threads=self.cpu_threads,
            )
        return self._recognizer


def recognize_crop(image_bytes: bytes, model_client: LocalTextRecognitionClient | None = None) -> dict[str, Any]:
    if not image_bytes:
        return {"text": "", "confidence": 0.0, "status": "empty_crop"}
    client = model_client or default_model_client()
    text, confidence = client.recognize(image_bytes)
    return {
        "text": text,
        "confidence": confidence,
        "status": "available",
    }


def recognize_crops(
    image_bytes_list: list[bytes],
    model_client: LocalTextRecognitionClient | None = None,
) -> dict[str, Any]:
    client = model_client or default_model_client()
    results = []
    for image_bytes in image_bytes_list:
        results.append(recognize_crop(image_bytes, model_client=client))
    return {"results": results}


def extract_text_from_rec_response(payload: Any) -> tuple[str, float]:
    texts: list[str] = []
    confidences: list[float] = []
    _collect_rec_values(payload, texts, confidences)
    deduped = _dedupe(texts)
    confidence = max(confidences) if confidences else (0.0 if not deduped else 0.42)
    return " ".join(deduped), _clamp(confidence)


def image_bytes_to_array(image_bytes: bytes) -> Any:
    import numpy as np
    from PIL import Image

    image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    return np.array(image)


def _collect_rec_values(payload: Any, texts: list[str], confidences: list[float]) -> None:
    payload = _as_dict(_as_list(payload))
    if isinstance(payload, str):
        if payload.strip():
            texts.append(payload)
        return
    if isinstance(payload, list):
        if payload and isinstance(payload[0], str):
            if payload[0].strip():
                texts.append(payload[0])
            if len(payload) > 1 and isinstance(payload[1], (int, float)):
                confidences.append(float(payload[1]))
            return
        for item in payload:
            _collect_rec_values(item, texts, confidences)
        return
    if not isinstance(payload, dict):
        return
    for key in ("text", "transcription", "rec_text", "label", "value"):
        value = payload.get(key)
        if isinstance(value, str) and value.strip():
            texts.append(value)
    for key in ("score", "confidence", "probability", "prob", "rec_score"):
        value = payload.get(key)
        if isinstance(value, (int, float)):
            confidences.append(float(value))
    for key, value in payload.items():
        if key in {"input_img", "vis_font"}:
            continue
        if isinstance(value, (dict, list)) or hasattr(value, "items"):
            _collect_rec_values(value, texts, confidences)


def _as_list(value: Any) -> Any:
    if hasattr(value, "tolist"):
        return value.tolist()
    return value


def _as_dict(value: Any) -> Any:
    if isinstance(value, dict):
        return value
    if hasattr(value, "items"):
        return dict(value.items())
    return value


def _dedupe(values: list[str]) -> list[str]:
    result: list[str] = []
    seen = set()
    for value in values:
        normalized = " ".join(str(value).split())
        if normalized and normalized not in seen:
            seen.add(normalized)
            result.append(str(value))
    return result


def _clamp(value: float) -> float:
    try:
        numeric = float(value)
    except (TypeError, ValueError):
        return 0.0
    if numeric < 0:
        return 0.0
    if numeric > 1:
        return 1.0
    return numeric


_MODEL_CLIENT: LocalTextRecognitionClient | None = None


def default_model_client() -> LocalTextRecognitionClient:
    global _MODEL_CLIENT
    if _MODEL_CLIENT is None:
        _MODEL_CLIENT = LocalTextRecognitionClient(
            rec_model_dir=os.getenv("PP_OCR_REC_MODEL_DIR", str(DEFAULT_REC_MODEL_DIR)),
            device=os.getenv("PP_OCR_DEVICE", "cpu"),
            cpu_threads=int(os.getenv("PP_OCR_CPU_THREADS", "4")),
        )
    return _MODEL_CLIENT


def reset_default_model_client() -> None:
    global _MODEL_CLIENT
    _MODEL_CLIENT = None
