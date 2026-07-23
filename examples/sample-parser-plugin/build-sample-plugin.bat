@echo off
setlocal EnableExtensions
cd /d "%~dp0\..\.."

if not exist src\apdu\parser\plugin\api\ApduParserPlugin.java (
  echo Plugin API sources are missing.
  exit /b 1
)

if exist examples\sample-parser-plugin\build rmdir /s /q examples\sample-parser-plugin\build
mkdir examples\sample-parser-plugin\build\classes

if defined JAVA_HOME (
  set "PATH=%JAVA_HOME%\bin;%PATH%"
)

set "SERVICE_DIR=examples\sample-parser-plugin\build\classes\META-INF\services"
mkdir "%SERVICE_DIR%"

set "COMPILE_STDERR=examples\sample-parser-plugin\build\compile.stderr"
javac -encoding UTF-8 -d examples\sample-parser-plugin\build\classes ^
  src\apdu\parser\plugin\api\ApduParserPlugin.java ^
  src\apdu\parser\plugin\api\PluginConstants.java ^
  src\apdu\parser\plugin\api\PluginDetectionResult.java ^
  src\apdu\parser\plugin\api\PluginParseResult.java ^
  examples\sample-parser-plugin\src\example\SamplePcscPlugin.java 2> "%COMPILE_STDERR%"
if errorlevel 1 (
  type "%COMPILE_STDERR%"
  exit /b 1
)
if exist "%COMPILE_STDERR%" del /q "%COMPILE_STDERR%"

(
  echo example.SamplePcscPlugin
) > "%SERVICE_DIR%\apdu.parser.plugin.api.ApduParserPlugin"

jar --create --file examples\sample-parser-plugin\build\sample-parser-plugin.jar -C examples\sample-parser-plugin\build\classes .
if errorlevel 1 exit /b 1

echo Built plugin: %CD%\examples\sample-parser-plugin\build\sample-parser-plugin.jar
exit /b 0
