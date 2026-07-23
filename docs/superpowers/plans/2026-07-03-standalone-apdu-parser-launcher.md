# Standalone APDU Parser Launcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the external-script launcher architecture with an internal standalone APDU parser application that detects, parses, analyzes, and extracts applets directly.

**Architecture:** Move all supported log-format parsing logic into internal `LogParser` implementations behind a shared registry. Rebuild the engine around per-log result folders and a simplified config, then replace the oversized launcher UI with one supported Swing workflow that drives the full analysis pipeline asynchronously.

**Tech Stack:** Java 17-compatible desktop app, Swing UI, plain Java I/O/NIO, existing internal analyzer logic.

---

### Task 1: Internal parser architecture

**Files:**
- Create: `src/LogParser.java`
- Create: `src/LogParserRegistry.java`
- Create: `src/InternalLogParsers.java`
- Test: `src/InternalParsersSelfTest.java`

- [ ] Define the parser interface and parse result model.
- [ ] Port the six current external extractor rules into isolated internal parsers.
- [ ] Add registry-based detection by extension plus content sample.
- [ ] Add self-tests covering supported and unsupported format detection.

### Task 2: Standalone engine workflow

**Files:**
- Modify: `src/ApduParserEngine.java`
- Create: `src/AppletExtractor.java`
- Modify: `config.json`
- Test: `src/ApduWorkflowSelfTest.java`

- [ ] Replace `ProcessBuilder`-based execution with direct parser calls.
- [ ] Save outputs by imported source log instead of parser folder.
- [ ] Generate `apdus.txt`, `analysis.txt`, `result.json`, and optional `applets/*`.
- [ ] Keep imported files stable, handle duplicate filenames, and store precise status/error state.
- [ ] Add workflow tests for empty, malformed, duplicate, unsupported, and successful inputs.

### Task 3: Main UI redesign

**Files:**
- Modify: `src/ApduParserLauncherUI.java`
- Modify: `src/ApduParserDesktopLauncher.java`
- Modify: `launch_ui.bat`

- [ ] Reduce the toolbar to `Import Logs`, `Analyze`, `Open Results`, plus a `More` menu.
- [ ] Replace the old register/extract/detect UI with a single log list and four result tabs.
- [ ] Keep drag-and-drop, per-row remove, and clear-all behavior.
- [ ] Run analysis off the EDT and support cancellation.
- [ ] Collapse diagnostics into a secondary panel instead of a primary layout region.

### Task 4: Cleanup unsupported/obsolete architecture

**Files:**
- Delete or stop compiling: `src/ApduParserLauncherFX.java`
- Modify: `src/ApduParserLauncher.java`
- Modify: `README.md`

- [ ] Make Swing the supported launcher path.
- [ ] Remove stale references to external parser projects, staged filenames, and runtime Java scripts.
- [ ] Rewrite the README around standalone usage and internal parser extension.

### Task 5: Verification and repo sweep

**Files:**
- Modify: `src/ApduAnalysisSelfTest.java`
- Modify: `src/ImportedLogsSelfTest.java`
- Modify: `src/UILayoutSelfTest.java`
- Modify: `src/RegisterLogTypeSelfTest.java` or replace with a more relevant coverage file

- [ ] Update existing tests instead of blindly removing them.
- [ ] Run the self-test suite and launcher compile path.
- [ ] Search for `ProcessBuilder`, `extractorFolder`, `scriptFile`, `stagedScriptFileName`, `stagedInputFileName`, `stagedOutputFileName`, `commandArgs`, and `../apdu`.
- [ ] Remove or explain every remaining occurrence before finishing.
