# Add Java Parser example

This folder contains a complete source parser that can be installed directly
from **More > Manage Parsers > Add Java Parser**.

Files:

- `SourcePcscPlugin.java`: parser source implementing `ApduParserPlugin`
- `sample.log`: a small input file for testing the parser

## Install and test

1. Open APDU Parser.
2. Select **More > Manage Parsers**.
3. Select **Add Java Parser**.
4. Choose `SourcePcscPlugin.java`.
5. After installation, select the new **Source Parser Example** row.
6. Select **Test Parser** and choose `sample.log`.

The application compiles the source with its bundled `javac.exe`, creates a
plugin JAR, validates it, and stores both the JAR and original source under:

```text
%LOCALAPPDATA%\APDUParser\plugins\installed\source_pcsc_plugin\
```

## Required interface methods

- `getId()`: permanent unique parser ID; use lowercase letters, numbers, and
  underscores.
- `getName()`: readable name shown in Manage Parsers.
- `getVersion()`: parser version such as `1.0.0`.
- `getPluginApiVersion()`: return
  `PluginConstants.CURRENT_PLUGIN_API_VERSION`.
- `getSupportedExtensions()`: accepted log extensions.
- `detect(...)`: examine the supplied sample and return a confident match only
  when markers specific to this log format are present.
- `parse(...)`: read the complete file and return normalized APDUs in their
  original order.

Each APDU returned by `parse(...)` should contain hexadecimal characters only,
for example:

```text
00A4040000
8010000000
```

Warnings are displayed to the user but do not fail a successful parse.

## Adapt it for a customer log

1. Copy `SourcePcscPlugin.java`.
2. Rename both the file and its `public class`.
3. Change the parser ID, display name, and version.
4. Replace `LOG_MARKER` with a reliable customer-log marker.
5. Replace `TX_APDU` and the extraction loop with that format's real rules.
6. Preserve APDU order and avoid matching card-to-terminal responses.

The source may use only the Java standard library and the APDU Parser plugin
API. For external dependencies, build a plugin JAR and use **Add Parser
Plugin** instead.
