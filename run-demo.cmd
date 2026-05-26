@echo off
cd /d "%~dp0"

if exist "%~dp0llm.local.cmd" call "%~dp0llm.local.cmd"

echo ==========================================
echo   Full-page LLM Structured Extraction Demo Launcher
echo ==========================================
echo.
echo  [1] Local Mode   - Start Java and Node dev servers
echo  [2] Docker Mode  - Start Docker containers
echo.
set /p choice="Select mode (1 or 2): "

if "%choice%"=="1" goto local
if "%choice%"=="2" goto docker

echo Invalid choice. Please enter 1 or 2.
pause
exit /b 1

:local
if "%LLM_API_KEY%%DASHSCOPE_API_KEY%"=="" (
    echo [ERROR] Set LLM_API_KEY or DASHSCOPE_API_KEY, or create llm.local.cmd with your OpenAI-compatible API settings.
    pause
    exit /b 1
)

echo.
echo Starting LOCAL mode through start-local.cmd...
echo.
"%~dp0start-local.cmd"
goto end

:docker
if "%LLM_API_KEY%%DASHSCOPE_API_KEY%"=="" (
    echo [ERROR] Set LLM_API_KEY or DASHSCOPE_API_KEY, or create llm.local.cmd with your OpenAI-compatible API settings.
    pause
    exit /b 1
)

echo.
echo Starting DOCKER mode...

docker info >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker is not running. Please start Docker Desktop first.
    pause
    exit /b 1
)

docker-compose up --build -d
if errorlevel 1 (
    echo [ERROR] Docker compose failed.
    pause
    exit /b 1
)

timeout /t 5 /nobreak >nul
echo.
docker-compose ps
echo.
echo Frontend : http://localhost:5184
echo Backend  : http://localhost:18081/api/health
echo OCR      : http://localhost:18092/health
echo.
echo To stop  : run-docker-stop.cmd
echo To logs  : docker-compose logs -f
echo.
goto end

:end
pause
