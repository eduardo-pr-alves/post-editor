@echo off
rem Compila o Post Editor sem Maven. Requer um JDK 8+ (javac e jar no PATH).
setlocal
cd /d "%~dp0"
if exist build rmdir /s /q build
mkdir build\classes
dir /s /b src\main\java\*.java > build\sources.txt
javac -encoding UTF-8 -source 8 -target 8 -d build\classes @build\sources.txt || exit /b 1
echo Main-Class: posteditor.App> build\manifest.txt
jar cfm post-editor.jar build\manifest.txt -C build\classes . || exit /b 1
echo.
echo Pronto! Execute com:  java -jar post-editor.jar
