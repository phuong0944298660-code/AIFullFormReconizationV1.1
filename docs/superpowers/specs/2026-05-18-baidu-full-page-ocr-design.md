# Baidu Full Page OCR Design

## Goal

Build a working copy of `AIFormReconization` under `AIFullFormReconization`, replacing the PaddleOCR-VL backend with Baidu OCR high-accuracy recognition with location. The new project must recognize whole pages directly. It must not use hard-coded field rectangles, crop boxes, or code-drawn coordinate overlays for field extraction.

## User Experience

The frontend keeps the split review surface:

- The left side shows the uploaded document page snapshot.
- The right side shows whole-document OCR text grouped by page by default.
- A secondary tab shows compact debug JSON.
- The UI labels the engine as Baidu OCR.

The app supports PDF and common image uploads. PDF files are rendered page by page on the backend before OCR, so Baidu receives one full-page image per page.

## Backend Architecture

The backend is a Spring Boot app copied from the source project shape. The OCR path is simplified:

1. `OcrController` accepts `multipart/form-data`.
2. `OcrDemoService` normalizes the upload into one or more full-page images.
3. `BaiduOcrHttpClient` retrieves an access token from Baidu using `BAIDU_OCR_API_KEY` and `BAIDU_OCR_SECRET_KEY`, caches it until near expiry, and calls the Baidu OCR high-accuracy endpoint for each full-page image.
4. `BaiduOcrResultMapper` maps Baidu `words_result` lines into the existing page/text/block response shape.
5. `BaiduOcrResultMapper` returns the full-page OCR text directly. It does not infer fields, use fixed field lists, or apply template coordinates.

Coordinates returned by Baidu may be retained inside compact debug data, but they do not drive extraction and are not drawn on the page.

## Configuration

Credentials are supplied by environment variables:

- `BAIDU_OCR_API_KEY`
- `BAIDU_OCR_SECRET_KEY`

Other tunables:

- `BAIDU_OCR_TIMEOUT_SECONDS`
- `BAIDU_OCR_LANGUAGE_TYPE`
- `BAIDU_OCR_DETECT_DIRECTION`

## Testing

Backend unit tests cover access-token request shape and Baidu result mapping. Frontend tests cover whole-document OCR presentation helpers. Build verification runs backend tests and frontend tests/build where dependencies are available.

## Non-Goals

- No fixed template-coordinate catalog.
- No crop-by-field OCR.
- No red/green bounding-box overlay UI.
- No dependency on the old PaddleOCR service.
