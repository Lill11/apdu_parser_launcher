# Java Parser CLI

## Entry point

- main class: `ApduParserCli`
- jar target: `parser/apdu-parser.jar`

## Build

```bat
build-parser.bat
```

## Command

Classpath form:

```bat
java -cp build\classes ApduParserCli --input "<log-file>" --json-out "<result.json>" --artifacts-dir "<artifact-dir>"
```

JAR form:

```bat
java -jar parser\apdu-parser.jar --input "<log-file>" --json-out "<result.json>" --artifacts-dir "<artifact-dir>"
```

## Arguments

- `--input <path>`
  - required
  - source log file
- `--json-out <path>`
  - required
  - UTF-8 structured JSON result
- `--artifacts-dir <path>`
  - optional
  - writes legacy compatible artifacts:
    - `apdus.txt`
    - `analysis.txt`
    - `result.json`
    - `errors.txt` when needed
    - `applets/`
- `--detect-only`
  - optional
  - performs parser detection without full extraction
- `--help`, `-h`
  - prints help text

## Exit codes

- `0`
  - success
- `1`
  - unsupported format
- `2`
  - malformed input
- `3`
  - parser failure
- `4`
  - invalid arguments
- `5`
  - output write failure

## Notes

- parser detection still uses the existing internal Java registry
- parser logic was not ported to Python
- the CLI uses UTF-8 JSON output
- technical diagnostics are written to `stderr`
- normal automation should consume the structured JSON, not human-readable console text
