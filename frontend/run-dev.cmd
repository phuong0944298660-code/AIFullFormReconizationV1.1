@echo off
cd /d "%~dp0"
set npm_config_cache=%~dp0.npm-cache
npm.cmd run dev >> "%~dp0frontend-dev.out.log" 2>&1
