@echo off
chcp 65001 >nul 2>&1
echo ============================================
echo   Prepare Embedded JRE (jlink)
echo ============================================
echo.

if "%JAVA_HOME%"=="" (
    echo [ERROR] JAVA_HOME is not set.
    echo Please install JDK 17+ and set JAVA_HOME.
    pause
    exit /b 1
)

echo JAVA_HOME: %JAVA_HOME%
echo.

set OUTPUT_DIR=%~dp0..\jre

if exist "%OUTPUT_DIR%" (
    echo Cleaning old JRE directory...
    rmdir /s /q "%OUTPUT_DIR%"
)

echo Creating minimal JRE with jlink...
echo.

"%JAVA_HOME%\bin\jlink" ^
    --module-path "%JAVA_HOME%\jmods" ^
    --add-modules java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.security.jgss,java.sql,java.xml,jdk.unsupported,jdk.crypto.ec ^
    --output "%OUTPUT_DIR%" ^
    --strip-debug ^
    --compress zip-6 ^
    --no-header-files ^
    --no-man-pages

if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] jlink failed.
    pause
    exit /b 1
)

echo.
echo ============================================
echo   JRE ready!
echo   Output: %OUTPUT_DIR%
echo ============================================
echo.
pause
