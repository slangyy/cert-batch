@echo off
chcp 65001 >nul 2>&1
echo ============================================
echo   Cert-Batch Build Script
echo ============================================
echo.

cd /d "%~dp0.."
echo Project Dir: %CD%
echo.

where java >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [ERROR] java not found in PATH
    goto :fail
)

where mvn >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [ERROR] mvn not found in PATH
    goto :fail
)

where node >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [ERROR] node not found in PATH
    goto :fail
)

echo [OK] java / mvn / node found.
echo.

echo [1/3] Building SpringBoot backend...
cd backend
call mvn clean package -DskipTests -q
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Backend build failed.
    goto :fail
)
echo       Backend build OK.
cd ..

if not exist "jre\bin\java.exe" (
    echo [2/3] Preparing embedded JRE...
    call scripts\prepare-jre.bat
    if %ERRORLEVEL% neq 0 (
        echo [ERROR] JRE preparation failed.
        goto :fail
    )
) else (
    echo [2/3] Embedded JRE already exists.
)

echo [3/3] Building Electron app...
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

:fail
echo.
echo ============================================
echo   BUILD FAILED! Check errors above.
echo ============================================
echo.
pause
exit /b 1
