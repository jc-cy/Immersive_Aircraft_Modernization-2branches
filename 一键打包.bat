@echo off
setlocal
cd /d "%~dp0"
call "%~dp0..\prepare-local-immersive-aircraft.bat" 1.20
if errorlevel 1 (
    echo Local Immersive Aircraft jar was not prepared; Gradle will try the remote dependency.
)
call gradlew.bat build
pause
