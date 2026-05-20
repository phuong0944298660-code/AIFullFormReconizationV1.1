@echo off
cd /d "%~dp0"

if exist "%~dp0baidu-ocr.local.cmd" call "%~dp0baidu-ocr.local.cmd"

echo ==========================================
echo Baidu Full-page OCR - Docker Mode
echo ==========================================
echo.

if "%BAIDU_OCR_AUTHORIZATION%"=="" (
    if "%BAIDU_OCR_API_KEY%"=="" (
        echo [ERROR] Set BAIDU_OCR_AUTHORIZATION, or set BAIDU_OCR_API_KEY and BAIDU_OCR_SECRET_KEY.
        pause
        exit /b 1
    )
    if "%BAIDU_OCR_SECRET_KEY%"=="" (
        echo [ERROR] Set BAIDU_OCR_AUTHORIZATION, or set BAIDU_OCR_API_KEY and BAIDU_OCR_SECRET_KEY.
        pause
        exit /b 1
    )
)

docker info >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker is not running. Please start Docker Desktop first.
    pause
    exit /b 1
)

echo Building and starting services...
echo   - Backend (Java + Baidu OCR): http://localhost:18081/api/health
echo   - Frontend (Vue)           : http://localhost:5184
echo.

docker-compose up --build -d

if errorlevel 1 (
    echo.
    echo [ERROR] Docker compose failed.
    pause
    exit /b 1
)

echo.
docker-compose ps
echo.
echo Frontend : http://localhost:5184
echo Backend  : http://localhost:18081/api/health
echo.
echo To stop  : run-docker-stop.cmd
echo To logs  : docker-compose logs -f
pause
