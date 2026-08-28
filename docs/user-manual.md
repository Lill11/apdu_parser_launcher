# APDU Parser User Manual

This manual is for normal users of the portable Windows build.

Main goal:

1. open the app
2. import one or more customer logs
3. run analysis
4. review APDUs and errors
5. export or open the results

## 1. Start the App

Open:

```text
APDUParser.exe
```

You do not need to install:

- Python
- Java
- `JAVA_HOME`
- external parser scripts

If you received `APDUParser-Portable.zip`, unzip it first and then open `APDUParser.exe`.

## 2. Main Workflow

Normal workflow:

1. click `Import Logs`, or drag files into the drop area
2. select the log you want to inspect
3. click `Analyze`
4. review the output tabs on the right
5. click `Open Results` if you want to open the generated files

Supported input examples:

- `.log`
- `.txt`
- `.htm`
- `.html`

## 3. Main Screen Overview

The main window has two working areas:

### Left side

- drop zone
- imported log list

### Right side

- filter chips such as `ALL`, `ES10`, `FETCH/TR`, `LSI`
- result tabs:
  - `APDUs`
  - `Analysis`
  - `Applets`
  - `Errors`

## 4. Import Logs

There are two ways to add logs:

### Option A: Import button

Click `Import Logs` and select one or more files.

### Option B: Drag and drop

Drag files from Windows Explorer into the drop area.

After import:

- the file appears in the imported log list
- the app keeps the file in its working area
- the file can be selected later for analysis

## 5. Analyze Logs

Click `Analyze` after importing logs.

The app will:

1. detect the best internal parser or installed plugin
2. extract APDUs
3. generate analysis output
4. save results under the user data folder

If the file is supported, you should see:

- parser name
- APDU output
- analysis events
- applet extraction results when available

## 6. Understanding the Result Tabs

### `APDUs`

Shows extracted APDU traffic in a compact format.

Use this tab when you want to:

- inspect raw command flow
- copy APDU sequences
- verify extraction quickly

### `Analysis`

Shows the enhanced analysis view.

Use this tab when you want to:

- inspect ES10 operations
- find key APDU events faster
- review sequence-level interpretation

### `Applets`

Shows extracted applet-related output if available.

### `Errors`

Shows technical errors and parser failures.

Use this tab when:

- a file is unsupported
- a parser failed
- Java source plugin compilation failed

### Cold Reset markers

When a supported log contains parser-specific evidence of a physical card
reset, the result timeline and exported `apdus.txt` contain a standalone:

```text
RESET
```

`RESET LSE` / `RESET LSI` commands, `REFRESH_[RESET]`, warm-reset debug text,
and ATR-looking bytes inside APDU responses are not converted into Cold Reset
events.

Legacy Java extractors can preserve a physical reset by writing an exact
standalone `RESET` line between their APDU lines:

```text
RESET
00A4040000
80E2910003BF2E00
RESET
00A40000023F00
```

## 7. Filters

### Generate Java from a China Unicom HTML report

1. Import the China Unicom `.html` or `.htm` report and click **Analyze**.
2. Select the completed report and open the **APDUs** tab.
3. Click **Generate Java**.
4. Use **Copy** or **Export Java** in the preview dialog.

The generated file is a complete Java test class in package `javaTest`. It calls `test.reset()` once, preserves APDU order, and splits long reports into methods containing at most 50 APDUs. The class and `.java` filename are derived from the HTML filename. If the source HTML does not define an Expected SW for a command, the output contains `// TODO: Expected SW not found in source HTML`. The application does not assume `9000` or reuse the actual card response.

The filter chips above the result tabs help reduce noise.

Available filters may include:

- `ALL`
- `ES10`
- `FETCH/TR`
- `LSI`

Only one filter is active at a time.

Use them to focus on:

- all detected events
- ES10 operations
- FETCH / Terminal Response traffic
- LSI-related traffic

## 8. Unsupported Logs

If no parser matches the file:

- the log remains visible in the imported list
- the status is shown as unsupported
- technical details appear in `Errors`

What to do next:

1. confirm the file is really an APDU-related log
2. check whether the source format is one of the supported types
3. install a parser plugin if your team has one
4. add a Java parser if you are extending the tool internally

## 9. Open Results

Click `Open Results` to open the generated result folder in Windows Explorer.

Typical output files:

- `apdus.txt`
- `analysis.txt`
- `errors.txt`
- `result.json`
- extracted applet files when available

## 10. Manage Parsers

Open:

```text
More > Manage Parsers
```

From there you can:

- review built-in parsers
- install a parser plugin JAR
- install a Java source parser
- enable or disable user-installed parsers
- remove user-installed parsers
- recompile Java source parsers
- view compilation errors

Built-in parsers:

- cannot be removed
- are always part of the application

The portable package also ships with `Ix USIM APDU Extractor OH`. On first launch it is copied to the user plugin directory automatically and appears in Manage Parsers as a legacy Java extractor. Its source is preserved so it can be recompiled, disabled, or removed like other user plugins.

## 11. Add a Parser Plugin

If your team already has a compiled parser plugin:

1. open `More > Manage Parsers`
2. click `Add Parser Plugin`
3. choose the `.jar` file
4. validate and install it

After installation:

- it appears in the parser list
- it can be enabled or disabled
- it is stored under the user plugin directory

## 12. Add a Java Parser

If your team has a parser implemented as a Java source file:

1. open `More > Manage Parsers`
2. click `Add Java Parser`
3. choose the `.java` file
4. let the app compile and validate it

Important:

- the source must implement the APDU Parser plugin API
- it is not enough to provide a random Java file with a `main` method

If compilation fails:

- the parser is not activated
- details are shown in the compilation log view

## 13. Where the App Stores Data

The app stores user data here:

```text
%LOCALAPPDATA%\APDUParser\
```

Main folders:

```text
config\
diagnostics\
logs\
output\
plugins\
temp\
```

User-installed parsers are stored under:

```text
%LOCALAPPDATA%\APDUParser\plugins\
```

## 14. Troubleshooting

### The app opens but no parser matches

Possible reasons:

- the file is not a supported format
- the log does not contain enough recognizable APDU markers
- the wrong file was imported

### A Java parser fails to compile

Check:

- `More > Manage Parsers`
- `View Compilation Errors`

### Drag and drop does not work

Try:

1. use `Import Logs`
2. confirm the file type is supported
3. make sure the file is not locked by another application

### Results look empty

Check:

- the selected file is the one you analyzed
- the log actually contains APDU traffic
- the `Errors` tab for technical details

## 15. Recommended Daily Usage

For QA / eSIM debugging, a good routine is:

1. import logs
2. analyze
3. check `Analysis` first
4. use filters like `ES10` or `LSI`
5. open `APDUs` for raw sequence confirmation
6. open `Errors` only when something looks wrong
7. use `Open Results` when you need to share files with teammates

## 16. For Internal Maintainers

If you are extending the tool, also read:

- [parser-plugin-development.md](parser-plugin-development.md)
- [java-parser-cli.md](java-parser-cli.md)
- [json-contract.md](json-contract.md)
