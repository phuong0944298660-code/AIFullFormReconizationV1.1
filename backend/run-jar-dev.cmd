@echo off
cd /d "%~dp0"

set RAG_ENABLED=false
set LLM_ENABLED=false

java -jar "%~dp0target\baidu-full-page-ocr-backend-0.1.0.jar" >> "%~dp0backend-dev.out.log" 2>&1
