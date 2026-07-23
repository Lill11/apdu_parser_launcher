@echo off
setlocal EnableExtensions
cd /d "%~dp0"
set "PYTHON_EXE=C:\Users\junli\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
set "PYTHONPATH=%CD%\py_src"
call build-parser.bat
if errorlevel 1 exit /b 1
"%PYTHON_EXE%" py_src\main.py
