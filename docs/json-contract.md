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
  "events": [],
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
- `eventSequence`
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

### `events[]`

Optional ordered parser events. Consumers written before this field was added can
continue reading `apdus[]`.

APDU event:

```json
{
  "sequence": 2,
  "type": "APDU",
  "apduIndex": 1,
  "command": "00A4040000",
  "response": "9000",
  "sourceLine": 8
}
```

Cold Reset event:

```json
{
  "sequence": 1,
  "type": "RESET",
  "resetType": "COLD_RESET",
  "label": "RESET",
  "atr": "3B9F96803FC7838031E073F62113674B0758E0240200A1",
  "sourceLine": 7
}
```

`sequence` preserves event order. `apduIndex` counts only APDU commands, so a
RESET does not change existing APDU numbering.

#### Parser-specific Cold Reset rules

Cold Reset detection is performed inside each parser. There is no global
`RESET` or `3B` search.

PCSC uses the confirmed standalone transport receive record:

```regex
^\s*(?:INFO\s+\S+\s+)?\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3,6}\s+<--\s+(3B(?:[0-9A-Fa-f]{2}){7,})\s*$
```

This accepts the confirmed standalone PCSC ATR receive line, with or without
the `INFO root:lib_tmsLogger.py:36` logging prefix. It does not match `[PCSC]`
APDU responses, expected/actual assertion text, ATR bytes embedded in APDU
payloads, RESET LSE APDUs, or `REFRESH_[RESET]` descriptions.

Other built-in formats use their own evidence:

| Parser | Required reset evidence |
| --- | --- |
| Honor APDU_TX | A `MOD_SIM_BASELINE_UH` cold-reset/power/activation completion record followed within 12 records by a dedicated `ATR_REPORT`, `CARD_ATR`, or `SIM_ATR` record. `APDU_rx` is excluded. |
| OPPO TXDATA | One complete `Type = ATR RX DATA` transport record. Split ATR records are assembled before the event is emitted. `MMGSDI_CARD_INSERTED_EVT` alone is ignored. |
| OH bytes | A valid ATR in a card-to-terminal frame (`01 01`), immediately followed by a terminal-to-card Configure LSI command (`80 7C 04 00`). |
| UNISOC USIMDRV | A same-slot `SimPowerOff`/cold-reset-start, then `SimPowerOn`/voltage activation, then a dedicated `SimGetATR`/`SimValidateATR` record. `is in warm reset:0` and `SIM_SendInstrCode active sim card` are ignored. |
| HTML APDU report | An exact report event `APDU: Reset` followed by an independent `ATR:` report row before the next APDU. A plain APDU-only HTML table cannot recover a missing reset. |
| Legacy Java extractor | An exact standalone output line `RESET`. The marker may be mixed with APDU lines and retains output order. |

The ATR validator parses the ATR interface-byte and historical-byte structure.
It is only applied after the parser has identified a transport-specific ATR
record; arbitrary APDU payload bytes beginning with `3B` are never scanned.

### `analysis[]`

Each analysis item contains:

- `index`
- `eventSequence`
- `type`
  - `apdu` or `reset`
- `title`
- `message`
- `severity`
- `statusWord`
- `tag`
- `sourceLine`
- `resetType`
- `atr`

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

See [success.json](examples/success.json)

## Example unsupported-format JSON

See [unsupported.json](examples/unsupported.json)

## Example parser-error JSON

See [parser-error.json](examples/parser-error.json)
