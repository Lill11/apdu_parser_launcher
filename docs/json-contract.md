# Parser JSON Contract

Schema version: `1`

Encoding: `UTF-8`

The Java parser CLI writes one machine-readable JSON document per invocation.

## Top-level structure

```json
{
  "schemaVersion": 1,
  "parserVersion": "1.0.0",
  "success": true,
  "status": "completed",
  "message": "Completed",
  "generatedAt": "2026-07-09T15:48:33.410339700Z",
  "sourceFile": "C:\\logs\\sample.log",
  "sourceFileName": "sample.log",
  "detectedParser": {
    "id": "honor_apdutx",
    "displayName": "Honor APDU_TX",
    "supported": true
  },
  "summary": {
    "apduCount": 3,
    "analysisEventCount": 4,
    "appletCount": 1,
    "warningCount": 0,
    "exitCode": 0
  },
  "apdus": [],
  "analysis": [],
  "applets": {},
  "warnings": [],
  "errors": [],
  "outputFiles": {}
}
```

## Fields

### `schemaVersion`

- integer
- current value: `1`

### `parserVersion`

- string
- current value: `1.0.0`

### `success`

- boolean
- `true` only when exit code is `0`

### `status`

- string
- values currently emitted:
  - `completed`
  - `detected`
  - `unsupported`
  - `malformed_input`
  - `parser_failure`
  - `invalid_arguments`

### `detectedParser`

- `id`
  - stable parser identifier
- `displayName`
  - human-readable parser name
- `supported`
  - `true` only when a parser matched

### `summary`

- `apduCount`
  - number of extracted command APDUs
- `analysisEventCount`
  - command events plus reset markers
- `appletCount`
  - number of applet files extracted
- `warningCount`
  - parser warnings
- `exitCode`
  - numeric process result code

### `apdus[]`

Each APDU entry contains:

- `index`
- `command`
- `response`
- `commandName`
- `headline`
- `statusWord`
- `severity`
- `tag`
- `sourceLine`
- `filters`
- `note`

`command` and `response` are uppercase hex strings.

### `analysis[]`

Each analysis item contains:

- `index`
- `type`
  - `apdu` or `reset`
- `title`
- `message`
- `severity`
- `statusWord`
- `tag`
- `sourceLine`

### `applets`

- `status`
  - `extracted`
  - `no_applets`
  - `not_applicable`
- `message`
- `allClean`
- `files[]`

Each `files[]` entry contains:

- `name`
- `lines`

### `warnings[]`

- parser warnings

### `errors[]`

Each error entry contains:

- `code`
- `message`
- `details`

### `outputFiles`

- `json`
- `artifactsDir`
- `apduText`
- `analysisText`
- `errorsText`
- `legacyResultJson`
- `stderrLog`

## Example success JSON

See [success.json](C:/Users/junli/Documents/Codex/apdu_parser_launcher/docs/examples/success.json)

## Example unsupported-format JSON

See [unsupported.json](C:/Users/junli/Documents/Codex/apdu_parser_launcher/docs/examples/unsupported.json)

## Example parser-error JSON

See [parser-error.json](C:/Users/junli/Documents/Codex/apdu_parser_launcher/docs/examples/parser-error.json)
