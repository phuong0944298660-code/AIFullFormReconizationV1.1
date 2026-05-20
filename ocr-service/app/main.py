from __future__ import annotations

from fastapi import FastAPI, File, HTTPException, UploadFile

from app.ocr_pipeline import recognize_crop, recognize_crops


app = FastAPI(title="Full-page LLM Field OCR Sidecar", version="0.1.0")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "full-page-field-ocr-sidecar"}


@app.post("/ocr/crop-recognize")
async def crop_recognize(file: UploadFile = File(...)) -> dict:
    try:
        image_bytes = await file.read()
        return recognize_crop(image_bytes)
    except Exception as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc


@app.post("/ocr/crop-recognize-batch")
async def crop_recognize_batch(files: list[UploadFile] = File(...)) -> dict:
    try:
        image_bytes_list = [await file.read() for file in files]
        return recognize_crops(image_bytes_list)
    except Exception as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
