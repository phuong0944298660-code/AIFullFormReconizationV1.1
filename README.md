# AIFullFormReconization

Full-page form understanding demo based on the original `AIFormReconization` project shape.

This version sends each PDF page or image page to an OpenAI-compatible multimodal LLM and asks the model to identify fields, filled handwritten content, checked options, field regions, confidence, and structured JSON. It does not rely on a preset field list, manually marked coordinate boxes, or fixed crop sizes.

## Configuration

Set LLM credentials before starting the backend:

```cmd
set LLM_BASE_URL=https://apie.zhisuaninfo.com/v1
set LLM_MODEL=Qwen3.6-35B-A3B
set LLM_API_KEY=replace-with-your-key
```

Do not commit real model credentials into this repository.

For double-click startup, copy `llm.local.cmd.example` to `llm.local.cmd` and fill in the credential. The real local file is ignored by `.gitignore`.

The legacy Baidu OCR settings remain in the backend configuration for optional experiments, but the current demo path is LLM-only.

## Local Run

```cmd
run-demo.cmd
```

Or start the services separately:

```cmd
cd backend
mvn spring-boot:run
```

```cmd
cd frontend
npm run dev
```

Open `http://127.0.0.1:5184`. The backend runs on `http://127.0.0.1:18081`.

## Verification

```cmd
cd backend
mvn test
```

```cmd
cd frontend
npm test
npm run build
```
