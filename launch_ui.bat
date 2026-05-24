@echo off
setlocal EnableExtensions
cd /d "%~dp0"

where java >nul 2>nul
if errorlevel 1 (
  if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "PATH=%JAVA_HOME%\bin;%PATH%"
)
where java >nul 2>nul
if errorlevel 1 (
  for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-*") do (
    if exist "%%~fD\bin\java.exe" set "PATH=%%~fD\bin;%PATH%"
  )
)
where java >nul 2>nul
if errorlevel 1 (
  for /d %%D in ("C:\Program Files\Java\jdk-*") do (
    if exist "%%~fD\bin\java.exe" set "PATH=%%~fD\bin;%PATH%"
  )
)

where java >nul 2>nul
if errorlevel 1 (
  echo java not found. Please install a JDK 11+ and make sure java.exe is on PATH.
  exit /b 1
)

where javac >nul 2>nul
if errorlevel 1 (
  echo javac not found. This launcher needs a JDK, not only a JRE.
  echo Please install a JDK 11+ and make sure javac.exe is on PATH.
  exit /b 1
)

if not exist build\classes mkdir build\classes

if not "%JAVAFX_LIB%"=="" (
  echo Compiling JavaFX UI...
  javac --module-path "%JAVAFX_LIB%" --add-modules javafx.controls,javafx.graphics -d build\classes src\ApduOutputAnalyzer.java src\ApduParserEngine.java src\ApduParserLauncher.java src\ApduParserLauncherUI.java src\ApduParserDesktopLauncher.java src\ApduParserLauncherFX.java
  if %errorlevel%==0 (
    echo Launching JavaFX UI...
    java --module-path "%JAVAFX_LIB%;build\classes" --add-modules javafx.controls,javafx.graphics -cp build\classes ApduParserDesktopLauncher
  )
  if %errorlevel%==0 exit /b 0
  echo JavaFX launch failed. Falling back to Swing UI...
)

echo Compiling Swing fallback UI...
javac -d build\classes src\ApduOutputAnalyzer.java src\ApduParserEngine.java src\ApduParserLauncher.java src\ApduParserLauncherUI.java src\ApduParserDesktopLauncher.java
if errorlevel 1 exit /b 1

echo Launching Swing fallback UI...
java -cp build\classes ApduParserDesktopLauncher
endlocal
exit /b %errorlevel%
