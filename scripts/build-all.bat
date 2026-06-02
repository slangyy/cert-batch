@echo off
chcp 65001 >nul 2>&1
echo ============================================
echo   Cert-Batch Build Script
echo ============================================
echo.

cd /d "%~dp0.."
echo Project Dir: %CD%
echo.

where java >nul 2>nul || goto :no_java
where mvn >nul 2>nul || goto :no_mvn
where node >nul 2>nul || goto :no_node
echo [OK] java / mvn / node found.
echo.
goto :step1

:no_java
echo [ERROR] java not found in PATH
goto :fail
:no_mvn
echo [ERROR] mvn not found in PATH
goto :fail
:no_node
echo [ERROR] node not found in PATH
goto :fail

:step1
echo [1/3] Building SpringBoot backend...
cd backend
call mvn clean package -DskipTests -q
if not errorlevel 1 goto :backend_ok
echo [ERROR] Backend build failed.
cd ..
goto :fail
:backend_ok
echo       Backend build OK.
cd ..

:step2
if exist "jre\bin\java.exe" goto :jre_ok
echo [2/3] Preparing embedded JRE...
call scripts\prepare-jre.bat
if not errorlevel 1 goto :jre_ok
echo [ERROR] JRE preparation failed.
goto :fail
:jre_ok
echo [2/3] Embedded JRE ready.

:step3
echo [3/3] Building Electron app...
cd frontend
call npm install
if errorlevel 1 (
    echo [ERROR] npm install failed.
    cd ..
    goto :fail
)
call npm run electron:build
if errorlevel 1 (
    echo [ERROR] Electron build failed.
    cd ..
    goto :fail
)
echo       Electron build OK.
cd ..

echo.
echo ============================================
echo   BUILD SUCCESS!
echo   Output: frontend\dist-electron\
echo ============================================
echo.
echo [IMPORTANT]
echo   1. Start backend, GET /api/license/public-key
echo   2. Paste public key into frontend\electron\main.js LICENSE_PUBLIC_KEY
echo   3. Re-run: cd frontend ^&^& npm run electron:build
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
