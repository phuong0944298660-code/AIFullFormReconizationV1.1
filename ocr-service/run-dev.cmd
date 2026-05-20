@echo off
cd /d "%~dp0"

if "%PP_OCR_REC_MODEL_DIR%"=="" set PP_OCR_REC_MODEL_DIR=%~dp0..\models\PP-OCRv5_server_rec_infer
if "%PP_OCR_DEVICE%"=="" set PP_OCR_DEVICE=cpu
if "%PP_OCR_CPU_THREADS%"=="" set PP_OCR_CPU_THREADS=4
if "%PADDLE_PDX_CACHE_HOME%"=="" set PADDLE_PDX_CACHE_HOME=%~dp0..\.paddlex_cache
if "%FIELD_OCR_PORT%"=="" set FIELD_OCR_PORT=18092

if /I "%PYTHON_EXE%"=="python" set "PYTHON_EXE="
if "%PYTHON_EXE%"=="" if exist "%~dp0.venv\Scripts\python.exe" set "PYTHON_EXE=%~dp0.venv\Scripts\python.exe"
if "%PYTHON_EXE%"=="" if exist "%~dp0..\..\AIFormReconization\ocr-service\.venv\Scripts\python.exe" set "PYTHON_EXE=%~dp0..\..\AIFormReconization\ocr-service\.venv\Scripts\python.exe"
if "%PYTHON_EXE%"=="" set "PYTHON_EXE=python"

echo Using PYTHON_EXE=%PYTHON_EXE% >> "%~dp0ocr-service-dev.log"
"%PYTHON_EXE%" -m uvicorn app.main:app --host 127.0.0.1 --port %FIELD_OCR_PORT% >> "%~dp0ocr-service-dev.log" 2>&1
