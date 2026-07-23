@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "BUILD_ROOT=build\parser"
set "JAR_PATH=parser\apdu-parser.jar"
set "PLUGIN_API_JAR_PATH=parser\plugin-api.jar"
set "SOURCES_FILE=%BUILD_ROOT%\sources.txt"

if exist "%BUILD_ROOT%" rmdir /s /q "%BUILD_ROOT%"
if not exist "parser" mkdir "parser"
if exist "%JAR_PATH%" del /q "%JAR_PATH%"
if exist "%PLUGIN_API_JAR_PATH%" del /q "%PLUGIN_API_JAR_PATH%"
mkdir "%BUILD_ROOT%\classes"

if defined APDU_PARSER_JAVAC (
  set "JAVAC_CMD=%APDU_PARSER_JAVAC%"
) else (
  set "JAVAC_CMD=javac"
)

if defined APDU_PARSER_JAR (
  set "JAR_CMD=%APDU_PARSER_JAR%"
) else (
  set "JAR_CMD=jar"
)

echo Compiling parser sources...
dir /s /b src\*.java > "%SOURCES_FILE%"
"%JAVAC_CMD%" -d "%BUILD_ROOT%\classes" @"%SOURCES_FILE%"
if errorlevel 1 exit /b 1

echo Creating parser jar...
"%JAR_CMD%" --create --file "%JAR_PATH%" --main-class ApduParserCli -C "%BUILD_ROOT%\classes" .
if errorlevel 1 exit /b 1

echo Creating plugin API jar...
"%JAR_CMD%" --create --file "%PLUGIN_API_JAR_PATH%" -C "%BUILD_ROOT%\classes" apdu\parser\plugin\api
if errorlevel 1 exit /b 1

echo Parser jar created: %CD%\%JAR_PATH%
echo Plugin API jar created: %CD%\%PLUGIN_API_JAR_PATH%
exit /b 0
