from __future__ import annotations

from pathlib import Path

import pytest

from apdu_parser.core.result_mapper import ResultMappingError, map_result_payload


def test_map_result_payload_success():
    payload = {
        "schemaVersion": 1,
        "parserVersion": "1.0.0",
        "success": True,
        "status": "completed",
        "message": "Completed",
        "generatedAt": "2026-01-01T00:00:00Z",
        "sourceFile": str(Path("C:/logs/test.log")),
        "sourceFileName": "test.log",
        "detectedParser": {"id": "pcsc_terminal", "displayName": "PCSC Terminal", "supported": True},
        "summary": {"apduCount": 1, "analysisEventCount": 1, "appletCount": 0, "warningCount": 0, "exitCode": 0},
        "apdus": [{
            "index": 1, "command": "00A4040000", "response": "9000", "commandName": "SELECT",
            "headline": "SELECT", "statusWord": "9000", "severity": "OK", "tag": "",
            "sourceLine": 3, "filters": ["ES10"], "note": ""
        }],
        "analysis": [],
        "applets": {"status": "no_applets", "message": "", "allClean": [], "files": []},
        "warnings": [],
        "errors": [],
        "outputFiles": {"json": "", "artifactsDir": "", "apduText": "", "analysisText": "", "errorsText": "", "legacyResultJson": "", "stderrLog": ""},
    }
    result = map_result_payload(payload)
    assert result.detected_parser.parser_id == "pcsc_terminal"
    assert result.summary.apdu_count == 1
    assert result.apdus[0].category == "ES10"
    assert result.events == result.apdus


def test_map_result_payload_preserves_ordered_reset_events():
    payload = {
        "schemaVersion": 1,
        "detectedParser": {"id": "pcsc_terminal", "displayName": "PCSC Terminal", "supported": True},
        "summary": {"apduCount": 1, "analysisEventCount": 2, "appletCount": 0, "warningCount": 0, "exitCode": 0},
        "apdus": [{"index": 1, "eventSequence": 2, "command": "00A4040000"}],
        "events": [
            {
                "sequence": 1,
                "type": "RESET",
                "resetType": "COLD_RESET",
                "label": "RESET",
                "atr": "3B9F96803FC7838031E073F62113674B0758E0240200A1",
                "sourceLine": 7,
            },
            {"sequence": 2, "type": "APDU", "apduIndex": 1, "command": "00A4040000", "sourceLine": 8},
        ],
        "analysis": [],
        "applets": {},
        "outputFiles": {},
    }

    result = map_result_payload(payload)

    assert [event.command for event in result.events] == ["RESET", "00A4040000"]
    assert result.events[0].index is None
    assert result.events[0].category == "SYSTEM"
    assert result.events[0].description == "Cold Reset"
    assert result.events[0].reset_type == "COLD_RESET"


def test_map_result_payload_rejects_unknown_schema():
    payload = {"schemaVersion": 99}
    with pytest.raises(ResultMappingError):
        map_result_payload(payload)
