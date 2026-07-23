# APDU Parser

APDU Parser is a portable Windows desktop tool for internal QA and eSIM debugging.

It lets you:

1. import or drag customer logs
2. analyze them with the built-in parser engine
3. review APDUs, analysis events, applets, and errors
4. open exported results

You do not need to install Python, Java, or any external parser scripts.

## User Manual

Start here if you want a practical how-to guide:

- [User Manual](docs/user-manual.md)

## Quick Start

Use the portable build:

- `dist\APDUParser\APDUParser.exe`
- `dist\APDUParser-Portable.zip`

Normal user flow:

1. unzip `APDUParser-Portable.zip`
2. open `APDUParser.exe`
3. drag one or more log files into the drop area, or click `Import Logs`
4. click `Analyze`
5. review the results in:
   - `APDUs`
   - `Analysis`
   - `Applets`
   - `Errors`
6. click `Open Results` to open the output folder

## What the Portable Build Includes

The portable package already contains:

- the PySide6 desktop application
- the Java parser JAR
- a private bundled Java toolchain
- icons, styles, and default configuration

The application always uses the bundled tools:

```text
dist\APDUParser\runtime\bin\java.exe
dist\APDUParser\runtime\bin\javac.exe
dist\APDUParser\runtime\bin\jar.exe
```

It does not depend on:

- `JAVA_HOME`
- a system JRE or JDK
- external parser folders
- external Java source scripts

## Supported Log Formats

The current build includes internal parsers for:

- Honor `APDU_tx` / `APDU_rx`
- OPPO `Type = TX / RX`
- OH byte-stream logs
- Unisoc `USIMDRV`
- PC/SC terminal logs
- HTML APDU reports

If a file is not recognized, it stays visible in the app and is marked as unsupported.

## Where User Data Is Stored

The app does not write mutable data into the install folder.

User data is stored under:

```text
%LOCALAPPDATA%\APDUParser\
  config\
  diagnostics\
  logs\
  output\
  temp\
```

This is where the app keeps:

- user settings
- imported-log metadata
- generated results
- temporary working files
- diagnostics

## Output Files

For each analyzed log, the app generates a result folder under:

```text
%LOCALAPPDATA%\APDUParser\output\
```

Typical output files include:

- `apdus.txt`
- `analysis.txt`
- `errors.txt` when needed
- `result.json`
- `applets\...` when applet extraction is available

## Main UI

The supported desktop UI is the Python + PySide6 application under `py_src\apdu_parser\app.py`.

Main actions:

- `Import Logs`
- `Analyze`
- `Open Results`
- `More`

`More` contains secondary actions such as refresh, input/output folder shortcuts, and diagnostics/settings.

## Rebuilding the Portable App

For maintainers, the main Windows packaging command is:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build_windows.ps1
```

This build:

1. installs Python dependencies into `.venv`
2. runs Python tests
3. builds and tests the Java parser
4. copies a private compiler-capable JDK into `dist\APDUParser\runtime`
5. packages the app with `PyInstaller`
6. validates the packaged app, including plugin install and Java source compilation
7. creates `dist\APDUParser-Portable.zip`

## Important Notes

- The portable build is the supported delivery format.
- Users should launch `APDUParser.exe`.
- The portable build is the supported desktop delivery format.
- If you hand the tool to another user, send the ZIP or the whole `dist\APDUParser` folder.
