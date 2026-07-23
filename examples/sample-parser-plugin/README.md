# Sample Parser Plugin

This example shows a minimal precompiled `.jar` plugin for Phase A of the APDU Parser extension system.

It implements:

- `apdu.parser.plugin.api.ApduParserPlugin`
- UTF-8 source
- `META-INF/services/apdu.parser.plugin.api.ApduParserPlugin`

## Build

From the project root:

```bat
examples\sample-parser-plugin\build-sample-plugin.bat
```

The script creates:

```text
examples\sample-parser-plugin\build\sample-parser-plugin.jar
```

## Install through the UI

1. Open `APDUParser.exe`
2. Open `More > Manage Parsers`
3. Click `Add Parser Plugin`
4. Select `sample-parser-plugin.jar`
5. Use `Test Parser` with:
   - `examples\sample-parser-plugin\sample.log`

## Behavior

The sample plugin detects simple PC/SC logs that contain:

```text
SAMPLE_PLUGIN_PCSC
```

and extracts APDUs from lines such as:

```text
--> [PCSC] 00A4040000
```
