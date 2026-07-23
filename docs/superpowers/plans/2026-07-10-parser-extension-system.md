# Parser Extension System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a stable Java parser plugin system to APDU Parser so users can install, validate, manage, test, enable, disable, and remove parser extensions from the PySide6 UI without breaking built-in parsers.

**Architecture:** Keep Python as an orchestration/UI layer and keep Java as the only parser execution and plugin-discovery runtime. Extend the Java parser CLI with plugin discovery, metadata inspection, validation, source compilation, and parser testing commands; then add a Python parser-management service and Manage Parsers dialog that consume structured JSON responses only.

**Tech Stack:** Java 17, ServiceLoader-based plugin discovery, private bundled JDK/JRE, PySide6, PyInstaller, structured JSON CLI contract, existing parser/result mapper.

---

## Current Architecture Inspection

### Java parser boundary today
- `src/LogParser.java`
  - current built-in parser interface
  - only exposes `getId()`, `getDisplayName()`, `getSupportedExtensions()`, `supports(...)`, `parse(...)`
- `src/InternalLogParsers.java`
  - hardcoded built-in parser implementations only
- `src/LogParserRegistry.java`
  - discovers only built-in parsers via `InternalLogParsers.createDefaultParsers()`
  - current conflict handling is first-match by extension bucket, then sorted fallback
  - there is no plugin metadata, confidence score, enabled state, duplicate detection, or isolation
- `src/ApduParserProcessor.java`
  - produces schema version `1` structured JSON used by Python
  - assumes a single registry and current `LogParser` contract
- `src/ApduParserCli.java`
  - only supports parse flow via `--input/--json-out/--artifacts-dir/--detect-only/--request-file`
  - no parser list, no plugin validation, no plugin install/test flows

### Python/UI boundary today
- `py_src/apdu_parser/services/java_parser_service.py`
  - only knows how to invoke the Java CLI parse command via request JSON
- `py_src/apdu_parser/ui/main_window.py`
  - `More` menu currently has Settings / Cancel Running Jobs / Open Diagnostics
  - no parser management surface exists yet
- `py_src/apdu_parser/ui/widgets/filter_bar.py`
  - already uses `QButtonGroup(exclusive=True)` and checkable buttons
  - current visual bug likely comes from manual `checkedChip` property styling and not centralizing checked-state refresh

### Packaging/runtime constraint that blocks source-plugin support right now
- `build_windows.ps1` currently creates the bundled runtime with:
  - `jdeps ... --print-module-deps parser\apdu-parser.jar`
  - `jlink --add-modules java.base,java.desktop ...`
- resulting portable runtime includes `runtime/bin/java.exe` but **not** `runtime/bin/javac.exe`
- therefore the current packaged app **cannot compile `.java` parser sources** on an end-user machine

## Required Packaging Change Before Coding Source-Plugin Support

Before implementing `.java` source plugin installation, we must change packaging so the bundled application includes a compiler-capable private JDK/toolchain.

Recommended approach:
- keep the existing jlink runtime for parser execution if desired
- additionally bundle a private compiler toolchain under a stable internal path, for example:
  - `jdk/bin/javac.exe`
  - `jdk/bin/jar.exe`
  - `jdk/bin/java.exe`
- update `PathService` and Java/Python services to resolve compiler tools from the bundled private JDK, never from PATH or `JAVA_HOME`

Why this is required:
- `jlink` runtime images do not include `javac`
- user requirement explicitly forbids depending on system Java/JDK/PATH/`JAVA_HOME`
- source plugin installation cannot be implemented correctly without this packaging change

## Recommended Implementation Phases

### Phase A: Formalize Java plugin API and registry boundary
1. Introduce a new Java plugin API package, for example under `src/plugins/...`, with:
   - `ApduParserPlugin`
   - `PluginConstants` (`CURRENT_PLUGIN_API_VERSION = 1`)
   - `DetectionResult` with `matched`, `confidence`, `reason`
   - metadata model for plugin inspection/listing
2. Add an adapter so built-in parsers are exposed through the same plugin-facing abstraction without rewriting parser logic unnecessarily.
3. Extend registry/discovery into a plugin-aware registry that loads:
   - built-ins
   - enabled user-installed plugin JARs
4. Add per-plugin failure isolation and duplicate parser ID rejection.

### Phase B: Add Java-side plugin discovery, validation, and management CLI
1. Add plugin storage conventions under `%LOCALAPPDATA%\APDUParser\plugins\installed\...`
2. Add Java-side commands for structured JSON output:
   - `--list-parsers`
   - `--inspect-plugin <jar>`
   - `--validate-plugin <jar>`
   - `--test-parser <id> --input <file>`
   - `--reload-plugins` if safe, otherwise report restart required
3. Extend parse-time discovery to use the plugin-aware registry while keeping schema compatibility for parse results.
4. Add deterministic conflict handling:
   - highest confidence wins
   - explicit tie-break priority
   - conflict result if still ambiguous

### Phase C: Add Java source compilation and generated plugin JAR flow
1. Add Java service that compiles a single `.java` source file using bundled `javac` with UTF-8.
2. Validate source constraints:
   - one public class
   - filename/classname match
   - implements plugin interface
   - nonblank unique parser ID
   - supported API version
3. Generate:
   - compiled classes
   - `META-INF/services/<plugin interface fqcn>`
   - plugin metadata JSON
   - final plugin JAR
4. Validate generated JAR through the same Java plugin validation flow before install.
5. Preserve source under installed plugin directory and only replace old working JAR after successful recompile.

### Phase D: Add Python parser-management integration
1. Extend `java_parser_service.py` with structured management operations:
   - list parsers
   - validate plugin
   - install source plugin
   - install jar plugin
   - enable/disable/remove/recompile/test parser
2. Add Python-side models for parser metadata, validation state, test results, and action results.
3. Keep all plugin execution/validation inside Java subprocesses; Python must not load Java classes directly.

### Phase E: Add Manage Parsers UI
1. Add `More > Manage Parsers` in `py_src/apdu_parser/ui/main_window.py`
2. Add a `ManageParsersDialog` with:
   - table/list of parsers
   - details panel
   - actions based on parser source type/state
3. Implement flows for:
   - Add Java Parser
   - Add Parser Plugin
   - Enable/Disable
   - Remove
   - View Details
   - Test Parser
   - Recompile
   - View Compilation Errors
4. Make built-in parsers non-removable and only disable-able where policy allows.

### Phase F: Fix filter visual-state bug
1. Simplify `FilterBar` checked-state handling to rely on exclusive checkable buttons and QSS `:checked`
2. Remove manual styling drift so `All`, `ES10`, `FETCH/TR`, and `LSI` always show exactly one selected state

### Phase G: Tests, docs, example plugin, packaging update
1. Java tests for plugin API, discovery, duplicate handling, validation, source compilation, parser test command, and broken plugin isolation
2. Python tests for management service JSON flows and UI state rules
3. Add `docs/parser-plugin-development.md`
4. Add `examples/sample-parser-plugin/`
5. Update packaging to bundle the compiler-capable private JDK and document size impact

## Files Likely To Be Added Or Changed

### Java
- Create: `src/pluginapi/ApduParserPlugin.java`
- Create: `src/pluginapi/PluginConstants.java`
- Create: `src/pluginapi/PluginDetectionResult.java`
- Create: `src/pluginapi/PluginMetadata.java`
- Create: `src/pluginapi/InstalledPluginRecord.java`
- Create: `src/plugin/PluginRegistry.java`
- Create: `src/plugin/PluginLoader.java`
- Create: `src/plugin/PluginValidator.java`
- Create: `src/plugin/PluginCompiler.java`
- Create: `src/plugin/PluginInstaller.java`
- Create: `src/plugin/PluginStateStore.java`
- Modify: `src/LogParser.java`
- Modify: `src/LogParserRegistry.java`
- Modify: `src/InternalLogParsers.java`
- Modify: `src/ApduParserProcessor.java`
- Modify: `src/ApduParserCli.java`
- Add new Java self-tests for plugin lifecycle

### Python
- Create: `py_src/apdu_parser/core/parser_management_models.py`
- Create: `py_src/apdu_parser/services/parser_management_service.py`
- Create: `py_src/apdu_parser/ui/dialogs/manage_parsers_dialog.py`
- Modify: `py_src/apdu_parser/services/java_parser_service.py`
- Modify: `py_src/apdu_parser/services/path_service.py`
- Modify: `py_src/apdu_parser/ui/main_window.py`
- Modify: `py_src/apdu_parser/ui/widgets/filter_bar.py`
- Add tests under `tests/unit` and `tests/integration`

### Docs / examples / packaging
- Create: `docs/parser-plugin-development.md`
- Create: `examples/sample-parser-plugin/README.md`
- Create: `examples/sample-parser-plugin/src/...`
- Modify: `build_windows.ps1`
- Modify: `APDUParser.spec`
- Modify: `README.md`

## Key Design Decisions

1. **Do not expose low-level parser command fields in UI**
   - parser management remains source/JAR based only
2. **Do not create a second parse-result schema**
   - plugins must emit the same `ProcessingResult`/JSON contract pipeline
3. **Use Java as the source of truth for parser inventory**
   - Python consumes structured JSON only
4. **Keep broken plugins isolated**
   - one plugin failure must not block built-ins or other plugins
5. **Prefer explicit restart-required messaging over unsafe hot reload**
   - we can add hot reload later only if it proves reliable

## Packaging Impact Assessment

This feature cannot be completed with the current bundled runtime alone.

Required packaging change:
- bundle a private compiler-capable JDK or compiler subset in addition to the execution runtime

Expected impact:
- package size will increase materially because `javac`, `jar`, and required JDK modules/tools must be distributed
- exact increase depends on whether we ship:
  - full private JDK, or
  - a curated compiler bundle

## Concise Execution Order

1. Package a compiler-capable private JDK/toolchain
2. Define plugin API v1 and registry adapters
3. Add Java plugin discovery and validation commands
4. Add JAR install flow
5. Add source compile-to-JAR flow
6. Add persistent plugin state and storage layout
7. Add Python parser-management service
8. Add Manage Parsers dialog
9. Fix filter selected-state bug
10. Add tests, docs, example plugin, and packaging updates
