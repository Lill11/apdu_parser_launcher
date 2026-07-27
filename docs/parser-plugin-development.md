# Parser Plugin Development

Phase B extends the Phase A parser plugin system with direct `.java` source installation.

The launcher now supports three extension paths:

1. trusted precompiled `.jar` parser plugins
2. Java source parsers that already implement `ApduParserPlugin`
3. old standalone Java extractors wrapped through compatibility mode

The plugin registry architecture stays the same. Source-installed parsers are compiled first, then validated through the same JAR validation flow used for Phase A plugins.

## Plugin API

Java plugins must implement:

```java
package apdu.parser.plugin.api;

public interface ApduParserPlugin {
    String getId();
    String getName();
    String getVersion();
    int getPluginApiVersion();
    java.util.List<String> getSupportedExtensions();
    PluginDetectionResult detect(java.nio.file.Path inputFile, byte[] sample) throws Exception;
    PluginParseResult parse(java.nio.file.Path inputFile) throws Exception;
}
```

Current API version:

```java
PluginConstants.CURRENT_PLUGIN_API_VERSION == 1
```

Required companion types:

- `apdu.parser.plugin.api.PluginConstants`
- `apdu.parser.plugin.api.PluginDetectionResult`
- `apdu.parser.plugin.api.PluginParseResult`

## Detection contract

Detection must inspect file content, not only file extension.

Plugins return:

- `matched`
- `confidence`
- `reason`

Guidance:

- Return a higher confidence only when the log signature is reliable.
- Keep the reason short and diagnostic-friendly.
- If multiple parsers tie on confidence and priority, the CLI reports a parser conflict instead of silently picking one.

## Parse contract

Plugins return a `PluginParseResult` containing:

- extracted APDUs
- parser warnings

The plugin does not write files directly. The existing Java parser CLI converts plugin output into the same structured JSON contract and artifact files used by built-in parsers.

## ServiceLoader registration

The plugin JAR must include:

```text
META-INF/services/apdu.parser.plugin.api.ApduParserPlugin
```

That file must contain the fully qualified implementation class name, for example:

```text
example.SamplePcscPlugin
```

## Supported install flows

Precompiled plugin install:

`More > Manage Parsers > Add Parser Plugin`

The application will:

1. validate the JAR
2. inspect parser metadata
3. reject duplicate or incompatible parser IDs
4. copy the plugin into the user plugin directory
5. persist enabled/disabled state

Java source install:

`More > Manage Parsers > Add Java Parser`

The application will:

1. inspect the selected `.java` source file
2. verify that the source declares one public class and implements `ApduParserPlugin`
3. resolve a compiler explicitly
4. compile the source with UTF-8 encoding
5. build an internal plugin JAR with `META-INF/services/...`
6. validate the generated JAR through the existing plugin validator
7. install it into the same plugin directory used for regular JAR plugins
8. preserve the original source file beside the installed plugin
9. write a compilation log for later review

The plugin is not activated if compilation or validation fails.

Legacy standalone Java extractor install:

`More > Manage Parsers > Add Legacy Java Extractor`

Compatibility mode is intended for older extractors that:

- have one public class
- expose `public static void main(String[] args)`
- accept either `<inputFile> <outputFile>` or `<inputFile>`
- generate APDU text output
- do not require Maven or external internet dependencies

The guided flow asks for:

1. source file
2. parser name / parser ID / version
3. supported extensions
4. command pattern
5. sample log for validation

The application will then:

1. inspect the source structure
2. compile the extractor with the bundled compiler path
3. generate an internal compatibility wrapper implementing `ApduParserPlugin`
4. package both classes into a plugin JAR
5. run the extractor against the sample log
6. parse the generated APDU text output
7. install the parser only if the test succeeds

Legacy output may contain an exact standalone `RESET` line mixed with APDUs.
The wrapper removes that marker from the APDU count and converts it to an
ordered `COLD_RESET` event. Other text containing `RESET` is ignored.

The bundled Ix_USIM OH extractor uses a stricter source-format rule. It emits
`RESET` only when one parsed record:

- is incoming (`ME <---- Ix_USIM`)
- begins directly with byte `3B`
- has trailing description text containing `card init` (case-insensitive)

The channel is not fixed, so `I0_USIM`, `I1_USIM`, and other numeric Ix_USIM
instances are supported. RESET LSE commands, incoming APDU responses containing
an ATR, `REFRESH_[RESET]`, and incoming `3B` data without `card init` remain
unchanged and do not produce a reset event.

Legacy extractors appear in Manage Parsers as:

`Source type: Legacy Java Extractor`

They also show:

- original source filename
- detected main class
- command pattern
- last compiled
- last tested
- last stderr

## User plugin storage

Installed plugins are stored under:

```text
%LOCALAPPDATA%\APDUParser\
  plugins\
    builtins.json
    installed\
      <parser-id>\
        plugin.jar
        metadata.json
        compile.log
        source\
          <original-file>.java
```

Notes:

- built-in parser enabled/disabled state is stored in `builtins.json`
- user-installed plugins are never written into `Program Files`
- plugin metadata is persisted separately from the bundled parser JAR
- source-installed parsers are shown as `Source type: Java Source` in `Manage Parsers`
- legacy standalone extractors are shown as `Source type: Legacy Java Extractor` in `Manage Parsers`

## Compiler resolution strategy

The launcher does not silently use a random `javac` from `PATH`.

Compiler resolution order:

1. Bundled private compiler path: `runtime/bin/javac.exe`
2. Java system property: `apdu.parser.javac`
3. Environment variable: `APDU_PARSER_JAVAC`
4. Otherwise fail with a clear `MISSING_COMPILER` result

This keeps development explicit today and leaves a clean path for Phase C packaging with a bundled compiler-capable private JDK/runtime.

## CLI commands

The Java parser CLI now supports:

```text
java -jar parser\apdu-parser.jar --list-parsers --json-out <result.json>
java -jar parser\apdu-parser.jar --validate-plugin <plugin.jar> --json-out <result.json>
java -jar parser\apdu-parser.jar --inspect-plugin <plugin.jar> --json-out <result.json>
java -jar parser\apdu-parser.jar --inspect-legacy-source <extractor.java> --json-out <result.json>
java -jar parser\apdu-parser.jar --install-plugin <plugin.jar> --json-out <result.json>
java -jar parser\apdu-parser.jar --install-source <plugin.java> --json-out <result.json>
java -jar parser\apdu-parser.jar --install-legacy-source <extractor.java> --parser-name <name> --parser-id <id> --supported-extensions <csv> --sample-input <log> --json-out <result.json>
java -jar parser\apdu-parser.jar --enable-parser <parser-id> --json-out <result.json>
java -jar parser\apdu-parser.jar --disable-parser <parser-id> --json-out <result.json>
java -jar parser\apdu-parser.jar --remove-plugin <parser-id> --json-out <result.json>
java -jar parser\apdu-parser.jar --recompile-parser <parser-id> --json-out <result.json>
java -jar parser\apdu-parser.jar --test-parser <parser-id> --input <sample.log> --json-out <result.json>
```

All management commands return structured JSON. Python does not parse arbitrary console text.

## Example projects

See:

- `examples\sample-parser-plugin\README.md`
- `examples\sample-parser-plugin\src\example\SamplePcscPlugin.java`
- `examples\sample-source-parser\README.md`
- `examples\sample-source-parser\SourcePcscPlugin.java`

Build the precompiled sample with:

```bat
examples\sample-parser-plugin\build-sample-plugin.bat
```

Install the sample source parser from the UI:

1. Open `More > Manage Parsers`
2. Click `Add Java Parser`
3. Select `examples\sample-source-parser\SourcePcscPlugin.java`
4. Confirm that it appears as `Java Source`
5. Use `Recompile` after editing the preserved source file if needed
6. Use `View Compilation Errors` to inspect the compile log or failure diagnostics

Install the sample legacy extractor from the UI:

1. Open `More > Manage Parsers`
2. Click `Add Legacy Java Extractor`
3. Select `examples\sample-legacy-extractor\LegacyPcscExtractor.java`
4. Set a parser name and parser ID
5. Keep command pattern as `<inputFile> <outputFile>`
6. Choose a sample log
7. Confirm that the test succeeds before installation

## Recompile behavior

Source-installed parsers expose two source-only actions in `Manage Parsers`:

- `Recompile`
- `View Compilation Errors`

Recompile rules:

- the preserved source file is compiled again
- the new temporary JAR is validated before replacement
- the installed plugin JAR is replaced only after successful validation
- failed recompiles keep the previous working plugin active
- failure diagnostics are saved to `compile.log`

## Phase C packaging note

The portable desktop build is expected to include a private compiler-capable runtime or JDK so `Add Java Parser` works on clean Windows machines without a developer JDK installed.

The same requirement applies to `Add Legacy Java Extractor`, because compatibility mode also compiles Java source on the user machine.
