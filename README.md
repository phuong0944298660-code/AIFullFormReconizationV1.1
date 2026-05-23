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

The frontend model dropdown also supports the Aliyun DashScope native model:

```cmd
set DASHSCOPE_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
set DASHSCOPE_MODEL=qwen3.6-35b-a3b
set DASHSCOPE_ENABLE_THINKING=true
set DASHSCOPE_API_KEY=replace-with-your-dashscope-key
```

Do not commit real model credentials into this repository.

For double-click startup, copy `llm.local.cmd.example` to `llm.local.cmd` and fill in the credential(s). The real local file is ignored by `.gitignore`.

The legacy Baidu OCR settings remain in the backend configuration for optional experiments, but the current demo path is LLM-only.

## Local Run

```cmd
start-local.cmd
```

This starts the standard local ports used by the current demo:

- Frontend: `http://127.0.0.1:5186/`
- Backend: `http://127.0.0.1:18083`
- Field OCR sidecar: `http://127.0.0.1:18092`

It also packages the backend jar, stops stale processes on those ports, loads `llm.local.cmd`, writes logs under `logs/`, and records PIDs in `tmp/local-services.json`.

Stop the local services with:

```cmd
stop-local.cmd
```

Or start the services separately for low-level debugging:

```cmd
cd backend
mvn -DskipTests package
set SERVER_PORT=18083
java -jar target\baidu-full-page-ocr-backend-0.1.0.jar
```

```cmd
cd frontend
npx vite --host 127.0.0.1 --port 5186 --strictPort
```

Open `http://127.0.0.1:5186`. The backend runs on `http://127.0.0.1:18083`.

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
