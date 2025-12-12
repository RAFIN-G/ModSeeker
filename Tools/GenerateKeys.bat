
@echo off
title ModSeeker Key Forge
color 0A
echo ===================================================
echo       MODSEEKER KEY GENERATOR (The Key Forge)
echo ===================================================
echo.
echo Compiling KeyGen.java...
"%JAVA_HOME%\bin\javac" KeyGen.java
if %errorlevel% neq 0 (
    echo [ERROR] Compilation failed! Check if JDK is installed and JAVA_HOME is set.
    pause
    exit /b
)

echo Running KeyGen...
"%JAVA_HOME%\bin\java" KeyGen

echo.
echo ===================================================
echo [SUCCESS] Keys generated in the Tools folder.
echo Please follow instructions above to update your code.
echo ===================================================
pause
