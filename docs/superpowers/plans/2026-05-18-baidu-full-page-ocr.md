# Baidu Full Page OCR Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans or equivalent careful inline execution. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a source-project-style OCR demo that uses Baidu OCR on whole pages and displays whole-document OCR text without field inference or hard-coded coordinate boxes.

**Architecture:** Reuse the Spring Boot + Vue/Vite shell from `AIFormReconization`. Replace the Paddle OCR gateway and fixed-coordinate extraction path with Baidu full-page OCR, PDF page rendering, and OCR line mapping.

**Tech Stack:** Java 17, Spring Boot 3.3, PDFBox, Vue 3, Vite, Node test runner.

---

### Task 1: Copy Project Shell

**Files:**
- Copy: `backend/**`
- Copy: `frontend/**`
- Copy and adjust: `README.md`, `docker-compose.yml`, run scripts

- [ ] Copy backend and frontend from `C:\Apps\MyProject\AIFormReconization` into `C:\Apps\MyProject\AIFullFormReconization`.
- [ ] Do not copy source `docs/outputs` or old generated OCR artifacts.
- [ ] Keep target `docs/5.12_full_tests` untouched.

### Task 2: Replace OCR Gateway

**Files:**
- Create: `backend/src/main/java/com/aiform/id995a/ocr/BaiduOcrGateway.java`
- Create: `backend/src/main/java/com/aiform/id995a/ocr/BaiduOcrHttpClient.java`
- Create: `backend/src/main/java/com/aiform/id995a/ocr/BaiduOcrPageRenderer.java`
- Modify: `backend/src/main/java/com/aiform/id995a/ocr/OcrDemoService.java`
- Modify: `backend/src/main/resources/application.yml`

- [ ] Add a gateway that accepts a full-page PNG byte array and returns raw Baidu JSON.
- [ ] Add token retrieval through `BAIDU_OCR_API_KEY` and `BAIDU_OCR_SECRET_KEY`.
- [ ] Render PDFs to page PNG images with PDFBox; pass image uploads through as a single page.
- [ ] Remove runtime dependency on PaddleOCR service URLs.

### Task 3: Map Whole-Page Results

**Files:**
- Create: `backend/src/main/java/com/aiform/id995a/ocr/BaiduOcrResultMapper.java`
- Modify: `backend/src/main/java/com/aiform/id995a/ocr/OcrDemoResponse.java` only if response shape needs a model-name tweak

- [ ] Convert `words_result[].words` and optional `location` into text lines and blocks.
- [ ] Build page snapshots from the rendered images.
- [ ] Return whole-page OCR text without inferring fields or using template coordinates.
- [ ] Keep debug JSON compact and avoid drawing overlays.

### Task 4: Update Frontend Presentation

**Files:**
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/ocrPresentation.js`
- Modify: `frontend/src/styles.css`
- Modify: `frontend/src/*.test.js`

- [ ] Replace Paddle wording with Baidu OCR wording.
- [ ] Make whole-document text the default result tab.
- [ ] Remove any UI copy suggesting coordinate boxes or crop recognition.
- [ ] Keep the left page snapshot and right text/JSON tabs.

### Task 5: Verify

**Files:**
- Test: `backend/src/test/java/**`
- Test: `frontend/src/*.test.js`

- [ ] Run backend unit tests.
- [ ] Run frontend tests.
- [ ] Build frontend.
- [ ] Start backend and frontend dev servers if dependencies and credentials are available.
- [ ] If credentials are absent, verify the app reports the missing Baidu credential error clearly.
