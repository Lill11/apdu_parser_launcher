@echo off
setlocal EnableExtensions
cd /d "%~dp0\.."

set "FX_JAVA_HOME="
if exist "C:\Program Files\BellSoft\LibericaJDK-17-Full\bin\java.exe" (
  set "FX_JAVA_HOME=C:\Program Files\BellSoft\LibericaJDK-17-Full"
)
if "%FX_JAVA_HOME%"=="" if exist "C:\Program Files\BellSoft\LibericaJDK-21-Full\bin\java.exe" (
  set "FX_JAVA_HOME=C:\Program Files\BellSoft\LibericaJDK-21-Full"
)
if "%FX_JAVA_HOME%"=="" if "%JAVAFX_LIB%"=="" (
  echo JavaFX runtime was not found automatically.
  echo Install BellSoft Liberica Full JDK or set JAVAFX_LIB to a JavaFX SDK lib folder.
  exit /b 1
)

if not "%FX_JAVA_HOME%"=="" (
  set "JAVA_EXE=%FX_JAVA_HOME%\bin\java.exe"
  set "JAVAC_EXE=%FX_JAVA_HOME%\bin\javac.exe"
) else (
  set "JAVA_EXE=java"
  set "JAVAC_EXE=javac"
)

where "%JAVAC_EXE%" >nul 2>nul
if errorlevel 1 if "%JAVAC_EXE%"=="javac" (
  echo javac not found. Please install a JDK 17+ first.
  exit /b 1
)

if exist javafx_preview\build rmdir /s /q javafx_preview\build
mkdir javafx_preview\build\classes

echo Compiling JavaFX preview...
if not "%FX_JAVA_HOME%"=="" (
  "%JAVAC_EXE%" --add-modules javafx.controls,javafx.graphics -d javafx_preview\build\classes src\ApduOutputAnalyzer.java src\ApduParserEngine.java javafx_preview\src\ApduQaWorkbenchFX.java
) else (
  "%JAVAC_EXE%" --module-path "%JAVAFX_LIB%" --add-modules javafx.controls,javafx.graphics -d javafx_preview\build\classes src\ApduOutputAnalyzer.java src\ApduParserEngine.java javafx_preview\src\ApduQaWorkbenchFX.java
)
if errorlevel 1 exit /b 1

echo Launching JavaFX preview...
if not "%FX_JAVA_HOME%"=="" (
  "%JAVA_EXE%" --add-modules javafx.controls,javafx.graphics -cp javafx_preview\build\classes ApduQaWorkbenchFX
) else (
  "%JAVA_EXE%" --module-path "%JAVAFX_LIB%" --add-modules javafx.controls,javafx.graphics -cp javafx_preview\build\classes ApduQaWorkbenchFX
)

endlocal
exit /b %errorlevel%
