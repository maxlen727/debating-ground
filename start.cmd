@echo off
cd /d "%~dp0"
where node >nul 2>nul
if errorlevel 1 (
  echo Node.js 22.18 or newer is required. Install it from https://nodejs.org/
  pause
  exit /b 1
)
if not exist node_modules (
  echo Installing dependencies...
  call npm install || (pause & exit /b 1)
)
if not exist dist\index.html (
  echo Building the interface...
  call npm run build || (pause & exit /b 1)
)
echo Open http://127.0.0.1:4173 in your browser after the server starts.
node server/server.ts
pause
