# Template Classification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect submitted document templates by footer ID and structure, record the classification in Markdown, and route ID 988A-specific checkbox extraction only to that template.

**Architecture:** Add a backend template detection service that runs after rendering and before post-processing. It identifies known footer IDs, falls back to a structure hash, writes a human-readable Markdown log plus machine-readable registry, and passes the detected template into selection-field refinement.

**Tech Stack:** Java 17, Spring Boot, PDFBox, local field OCR sidecar, JUnit.

---

### Task 1: Template Detection

**Files:**
- Create: `backend/src/main/java/com/aiform/id995a/ocr/DocumentTemplate.java`
- Create: `backend/src/main/java/com/aiform/id995a/ocr/TemplateDetectionService.java`
- Test: `backend/src/test/java/com/aiform/id995a/ocr/TemplateDetectionServiceTest.java`

- [ ] Write tests for footer ID from PDF text, footer ID from OCR crop, and unknown structure-hash fallback.
- [ ] Implement footer ID parsing and known template ID derivation.
- [ ] Implement rendered-page footer crop OCR fallback.
- [ ] Implement deterministic unknown template ID generation.

### Task 2: Classification Log

**Files:**
- Create: `backend/src/main/java/com/aiform/id995a/ocr/TemplateClassificationLogService.java`
- Test: `backend/src/test/java/com/aiform/id995a/ocr/TemplateClassificationLogServiceTest.java`
- Generate: `docs/template-classification.md`
- Generate: `data/template-classification.json`

- [ ] Write tests that a submitted filename is recorded and updated.
- [ ] Implement JSON registry persistence.
- [ ] Render Markdown from the registry for manual review.

### Task 3: OCR Pipeline Integration

**Files:**
- Modify: `backend/src/main/java/com/aiform/id995a/ocr/OcrDemoService.java`
- Modify: `backend/src/main/java/com/aiform/id995a/ocr/OcrJobService.java`
- Modify: `backend/src/main/java/com/aiform/id995a/ocr/OcrDemoResponse.java`
- Test: controller and OCR service tests as needed.

- [ ] Detect template after page rendering.
- [ ] Add `_template` metadata into structured JSON.
- [ ] Log each completed recognition to Markdown/JSON.

### Task 4: ID 988A Rule Routing

**Files:**
- Modify: `backend/src/main/java/com/aiform/id995a/ocr/SelectionFieldCropRefinementService.java`
- Test: `backend/src/test/java/com/aiform/id995a/ocr/SelectionFieldCropRefinementServiceTest.java`

- [ ] Write tests that ID 988A restores the three application-type fields.
- [ ] Write tests that ID 988B and ID 407 do not run ID 988A logic.
- [ ] Keep checkbox smudge as unchecked and allow clear dark or blue marks.

### Task 5: Sample Classification

**Files:**
- Generate/update: `docs/template-classification.md`
- Generate/update: `data/template-classification.json`

- [ ] Run the detector over `docs/5.12_full_tests`.
- [ ] Confirm the observed groups are based on footer/structure, not filename.
- [ ] Run backend and frontend tests.
