@echo off
setlocal EnableExtensions
cd /d "%~dp0"

where javac >nul 2>nul
if errorlevel 1 (
  if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "PATH=%JAVA_HOME%\bin;%PATH%"
)
if not exist "%JAVA_HOME%\bin\javac.exe" (
  for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-*") do (
    if exist "%%~fD\bin\javac.exe" set "PATH=%%~fD\bin;%PATH%"
  )
)
where javac >nul 2>nul
if errorlevel 1 (
  for /d %%D in ("C:\Program Files\Java\jdk-*") do (
    if exist "%%~fD\bin\javac.exe" set "PATH=%%~fD\bin;%PATH%"
  )
)

where javac >nul 2>nul
if errorlevel 1 (
  echo javac not found. Please install a JDK first.
  exit /b 1
)

if exist build rmdir /s /q build
if exist dist rmdir /s /q dist
mkdir build
mkdir dist

if not "%JAVAFX_LIB%"=="" (
  javac --module-path "%JAVAFX_LIB%" --add-modules javafx.controls,javafx.graphics -d build src\ApduOutputAnalyzer.java src\ApduParserEngine.java src\ApduParserLauncher.java src\ApduParserLauncherUI.java src\ApduParserDesktopLauncher.java src\ApduParserLauncherFX.java
) else (
  javac -d build src\ApduOutputAnalyzer.java src\ApduParserEngine.java src\ApduParserLauncher.java src\ApduParserLauncherUI.java src\ApduParserDesktopLauncher.java
)
if errorlevel 1 exit /b 1

jar --create --file build\ApduParserLauncher.jar --main-class ApduParserDesktopLauncher -C build .
if errorlevel 1 exit /b 1

where jpackage >nul 2>nul
if errorlevel 1 (
  echo jpackage not found. Compilation finished, but EXE packaging needs JDK 14+ with jpackage.
  exit /b 0
)

if not "%JAVAFX_LIB%"=="" (
  jpackage ^
    --module-path "%JAVAFX_LIB%" ^
    --add-modules javafx.controls,javafx.graphics ^
    --input build ^
    --dest dist ^
    --name ApduParserLauncher ^
    --main-jar ApduParserLauncher.jar ^
    --main-class ApduParserDesktopLauncher ^
    --type exe
) else (
  jpackage ^
    --input build ^
    --dest dist ^
    --name ApduParserLauncher ^
    --main-jar ApduParserLauncher.jar ^
    --main-class ApduParserDesktopLauncher ^
    --type exe
)

endlocal
exit /b %errorlevel%
