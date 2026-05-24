# APDU Parser Launcher

`APDU Parser Launcher` is a Windows desktop helper for QA / eSIM log parsing.

It takes raw customer logs, figures out which existing extractor should parse them, runs that extractor, writes clean APDU output into the `output/` folder, and can optionally run applet extraction from those APDUs.

This project is meant to be a practical internal tool:

- import customer logs
- preview which parser will be used
- run the parser
- inspect the result
- register a new parser without manually editing JSON in normal cases

## What This Tool Does

The launcher itself does not contain all parser logic.

Instead, it:

1. reads files from `input/`
2. matches them against parser definitions in `config.json`
3. stages the files into `work/`
4. runs the corresponding existing Java extractor
5. copies the final APDU output into `output/<parser-name>/`
6. optionally runs the existing `ExtractAppletsFromTxt` workflow against that APDU output

If no parser matches, the file is treated as unknown.

## Folder Layout

```text
apdu_parser_launcher/
  config.json
  README.md
  input/
  output/
  src/
  work/
```

- `input/`: imported source logs
- `output/`: final parsed APDU results
- `work/`: temporary staged runs for troubleshooting
- `config.json`: parser registry

## Requirements

- Windows
- JDK 11 or newer

The current launcher scripts already try to find a locally installed JDK automatically.

## Quick Start

Open the launcher with:

```powershell
launch_ui.bat
```

That script:

- checks Java
- compiles the launcher
- starts the desktop UI

## Normal User Flow

This is the recommended day-to-day flow.

1. Click `Import Logs` or drag files into `Drop Customer Logs`

Supported file types are usually:

- `.txt`
- `.log`
- `.html`
- `.htm`

2. Select a file in `Imported Logs`

3. Look at:

- `Detected Parser`
- `Parser Detection Status`
- `APDU Output Preview`

4. Choose run mode:

- `Detect Only`: preview matching only, do not execute parser
- `Execute Parser`: actually run extractor and generate output

5. Click `Parse Logs`

6. Optionally click `Extract Applets from APDUs`

7. Open results from:

- `APDU Output Preview`
- `Open Output Folder`

## Main Buttons

### Top Bar

- `Import Logs`: add supported log files into `input/`
- `Refresh`: reload counters, imported files, preview state, and output state
- `Register Log Type`: open the parser registration wizard
- `Open Input Folder`: open the raw imported files folder
- `Open Output Folder`: open the parsed output folder
- `Detect Only`: preview-only mode
- `Parse Logs`: run parser detection or full parsing
- `Extract Applets from APDUs`: run the existing applet extractor against the selected parsed APDU file

### Imported Logs

- select a log to inspect it
- `Remove Selected`: delete the currently selected imported file
- `Clear All`: remove all imported files
- `Delete` on each row: remove that single imported file

Deleting a log updates the list and counters automatically.

## What Each Main Panel Means

### Drop Customer Logs

Import new customer files into the tool.

### Imported Logs

Shows the currently imported files that are inside `input/`.

### Parser Detection Status

Shows whether the selected file matched a registered parser.

### APDU Output Preview

Shows the current output preview for the selected file.

If no output exists yet, the panel will tell you.

### Applet Extraction

This is a separate second step after APDU extraction.

Behavior:

- if no APDUs were extracted yet: `No APDUs available for applet extraction.`
- if APDUs exist but applet extraction has not been run yet: the tab asks you to click `Extract Applets from APDUs`
- if extraction runs but no applets are found: `No applet data found.`
- if applets are found: the applet tab previews `all_clean.lop` and lists generated `applet_*.lop` files

### Processing Console

Shows parsing progress and extractor console output.

It also shows console output from the applet extraction step.

### Run Mode

- `Detect Only`: no extractor run, no new output file written
- `Execute Parser`: run extractor and write output

Applet extraction is always manual and separate from `Detect Only`.

### Export & Delivery

Shortcut area for output folder and parser registration.

## Output Behavior

- matched logs go to `output/<parser-name>/`
- applet extraction results go to `output/<parser-name>/applets/<input-file-base>/`
- unmatched logs go to `output/unknown/`
- staged temporary files remain in `work/`

Example:

```text
output/
  honor_apdutx/
    honor_sample.txt
    applets/
      honor_sample/
        all_clean.lop
        applet_001.lop
  oppo_txdata/
    oppo_sample.txt
  unknown/
    unknown_log.txt
```

## Register Log Type

Use `Register Log Type` when you already have an existing extractor implementation and want this launcher to call it.

### Normal UI Fields

The normal form only asks for:

- `Parser name`
- `Supported extensions`
- `Extractor folder`
- `Main Java extractor script`

That is enough for most users.

### Advanced Settings

`Advanced Settings` is collapsed by default.

Only open it if your extractor needs custom staging names or special arguments.

Typical advanced cases:

- the extractor expects a fixed internal input file name
- the extractor writes to a fixed output file name
- the extractor needs command line arguments
- you want an extra filename regex filter

### Important Limitation

`Register Log Type` does not create a parser from scratch.

You still need an existing extractor folder and Java script somewhere in the workspace.

## Example: Adding a New Parser

Suppose you already have a folder:

```text
../vendor_xyz_extractor/
  ParseVendorXyz.java
```

Then in `Register Log Type`:

- `Parser name`: `vendor_xyz`
- `Supported extensions`: `.log,.txt`
- `Extractor folder`: `../vendor_xyz_extractor`
- `Main Java extractor script`: `ParseVendorXyz.java`

Only use `Advanced Settings` if that extractor requires special staged names.

## Config File

All parser definitions live in `config.json`.

Each parser typically contains:

- `name`
- `extractorFolder`
- `scriptFile`
- `stagedScriptFileName`
- `stagedInputFileName`
- `stagedOutputFileName`
- `outputExtension`
- `extensions`
- optional `patterns`
- optional `fileNameRegex`
- optional `commandArgs`

The UI is designed so normal users do not need to edit all of those directly.

## CLI Usage

If you want to run the launcher without the UI:

```powershell
javac -d build/classes src/ApduParserEngine.java src/ApduParserLauncher.java
java -cp build/classes ApduParserLauncher --dry-run
```

Run real parsing:

```powershell
java -cp build/classes ApduParserLauncher
```

## Build EXE

If your machine has `javac`, `jar`, and `jpackage`:

```powershell
build_exe.bat
```

This prepares a packaged Windows app under `dist/`.

## Common Questions

### Why does a file appear in Imported Logs but not parse?

Possible reasons:

- no registered parser matches it
- the file extension is supported but the content still does not match parser rules
- the extractor itself failed during execution

Check:

- `Parser Detection Status`
- `Processing Console`
- `output/unknown/`

### Why did output not update after I changed files manually?

Click `Refresh`, or return focus to the app window.

The launcher refreshes UI state when requested and when the window regains focus.

### Can I delete imported files inside the app?

Yes.

Use:

- `Delete` on a row
- `Remove Selected`
- `Clear All`

### Can I use image files, PSD, INDD, or random documents?

No.

This tool is for supported log-like files only.

### What if no parser matches?

The file is treated as unknown.

It will not be parsed, and the status will show `No match`.

## Notes

- Existing extractor scripts are not modified by this launcher.
- The applet step reuses the existing `ExtractAppletsFromTxt` logic from this workspace.
- Chinese file names are supported.
- Temporary staged names are used so old extractor scripts can keep their expected fixed file names.
- The core logic is shared by CLI and desktop UI through `ApduParserEngine.java`.

## If You Just Want the Short Version

Use this flow:

1. `launch_ui.bat`
2. `Import Logs`
3. select a file in `Imported Logs`
4. check `Detected Parser`
5. turn off `Detect Only` if you want real output
6. click `Parse Logs`
7. optionally click `Extract Applets from APDUs`
8. open results from `Open Output Folder`
