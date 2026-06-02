@echo off
chcp 65001 >nul 2>&1
echo ============================================
echo   Cert-Batch Build Script
echo ============================================
echo.

cd /d "%~dp0.."
echo Project Dir: %CD%
echo.

where node >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [ERROR] node not found in PATH
    goto :fail
)

echo [OK] node found.
echo.

echo [1/1] Building Electron app...
cd frontend
call npm install
if %ERRORLEVEL% neq 0 (
    echo [ERROR] npm install failed.
    goto :fail
)
call npm run electron:build
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Electron build failed.
    goto :fail
)
echo       Electron build OK.

echo.
echo ============================================
echo   BUILD SUCCESS!
echo   Output: frontend\dist-electron\
echo ============================================
echo.
pause
exit /b 0

::fail
echo.
echo ============================================
echo   BUILD FAILED! Check errors above.
echo ============================================
echo.
pause
exit /b 1
