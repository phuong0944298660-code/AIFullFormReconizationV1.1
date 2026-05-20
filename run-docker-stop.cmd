@echo off
cd /d "%~dp0"

echo Stopping Baidu full-page OCR Docker services...
docker-compose down

echo.
echo Services stopped.
pause
