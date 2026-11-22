@echo off
setlocal
cd /d "%~dp0"
where node >nul 2>&1
if errorlevel 1 (
  echo Node.js 24 or later is required.
  exit /b 1
)
node -e "process.exit(Number(process.versions.node.split('.')[0]) < 24 ? 1 : 0)"
if errorlevel 1 (
  echo Node.js 24 or later is required.
  exit /b 1
)
if not exist .env (
  copy /y .env.example .env >nul
  if errorlevel 1 exit /b 1
)
if not exist node_modules\.package-lock.json (
  call npm ci --no-audit --no-fund
  if errorlevel 1 exit /b 1
)
call npm run build
if errorlevel 1 exit /b 1
call npm start
