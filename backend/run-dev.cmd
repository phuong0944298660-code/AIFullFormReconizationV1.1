@echo off
cd /d "%~dp0"
if exist "%~dp0..\baidu-ocr.local.cmd" call "%~dp0..\baidu-ocr.local.cmd"
if exist "%~dp0..\llm.local.cmd" call "%~dp0..\llm.local.cmd"
set RAG_ENABLED=false
if not defined LLM_ENABLED set LLM_ENABLED=true
if not defined LLM_BASE_URL set LLM_BASE_URL=https://apie.zhisuaninfo.com/v1
if not defined LLM_MODEL set LLM_MODEL=Qwen3.6-35B-A3B
if not defined LLM_TIMEOUT_SECONDS set LLM_TIMEOUT_SECONDS=1200
if not defined LLM_PAGE_CONCURRENCY set LLM_PAGE_CONCURRENCY=4
if not defined FIELD_OCR_ENABLED set FIELD_OCR_ENABLED=true
if not defined FIELD_OCR_BASE_URL set FIELD_OCR_BASE_URL=http://127.0.0.1:18092
mvn.cmd spring-boot:run >> "%~dp0backend-dev.out.log" 2>&1
