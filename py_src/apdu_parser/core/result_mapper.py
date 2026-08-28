from __future__ import annotations

import json
from pathlib import Path

from apdu_parser.core.models import (
    AnalysisEvent,
    ApduStep,
    AppletFile,
    AppletPayload,
    ApduRow,
    DetectedParser,
    ErrorPayload,
    OutputFiles,
    ParseResult,
    ParserSummary,
)


class ResultMappingError(RuntimeError):
    pass


def map_result_file(json_path: Path) -> ParseResult:
    try:
        payload = json.loads(json_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ResultMappingError(f"Invalid parser JSON: {exc}") from exc
    return map_result_payload(payload)


def map_result_payload(payload: dict) -> ParseResult:
    if payload.get("schemaVersion") != 1:
        raise ResultMappingError(f"Unsupported schemaVersion: {payload.get('schemaVersion')}")

    try:
        detected = payload["detectedParser"]
        summary = payload["summary"]
        output_files = payload["outputFiles"]
    except KeyError as exc:
        raise ResultMappingError(f"Missing required field: {exc}") from exc

    apdus = [
        ApduRow(
            index=int(item.get("index", 0)),
            command=str(item.get("command", "")),
            response=str(item.get("response", "")),
            command_name=str(item.get("commandName", "")),
            headline=str(item.get("headline", "")),
            status_word=str(item.get("statusWord", "")),
            severity=str(item.get("severity", "")),
            tag=str(item.get("tag", "")),
            source_line=int(item.get("sourceLine", 0)),
            filters=[str(v) for v in item.get("filters", [])],
            note=str(item.get("note", "")),
            event_sequence=int(item.get("eventSequence", item.get("index", 0))),
        )
        for item in payload.get("apdus", [])
    ]

    events_payload = payload.get("events")
    if events_payload is None:
        events = list(apdus)
    else:
        events = []
        for item in events_payload:
            event_type = str(item.get("type", "APDU")).upper()
            if event_type == "RESET":
                events.append(
                    ApduRow(
                        index=None,
                        command="RESET",
                        response=str(item.get("atr", "")),
                        command_name="RESET",
                        headline="RESET",
                        status_word="",
                        severity="INFO",
                        tag="",
                        source_line=int(item.get("sourceLine", 0)),
                        filters=[],
                        note="Cold Reset",
                        event_sequence=int(item.get("sequence", 0)),
                        event_type="RESET",
                        reset_type=str(item.get("resetType", "")),
                        atr=str(item.get("atr", "")),
                    )
                )
                continue
            events.append(
                ApduRow(
                    index=int(item.get("apduIndex", 0)),
                    command=str(item.get("command", "")),
                    response=str(item.get("response", "")),
                    command_name=str(item.get("commandName", "")),
                    headline=str(item.get("headline", "")),
                    status_word=str(item.get("statusWord", "")),
                    severity=str(item.get("severity", "")),
                    tag=str(item.get("tag", "")),
                    source_line=int(item.get("sourceLine", 0)),
                    filters=[str(v) for v in item.get("filters", [])],
                    note=str(item.get("note", "")),
                    event_sequence=int(item.get("sequence", 0)),
                )
            )

    analysis = [
        AnalysisEvent(
            index=int(item.get("index", 0)),
            event_type=str(item.get("type", "")),
            title=str(item.get("title", "")),
            message=str(item.get("message", "")),
            severity=str(item.get("severity", "")),
            status_word=str(item.get("statusWord", "")),
            tag=str(item.get("tag", "")),
            source_line=int(item.get("sourceLine", 0)),
            event_sequence=int(item.get("eventSequence", item.get("index", 0))),
            reset_type=str(item.get("resetType", "")),
            atr=str(item.get("atr", "")),
        )
        for item in payload.get("analysis", [])
    ]

    applets_payload = payload.get("applets", {})
    applets = AppletPayload(
        status=str(applets_payload.get("status", "")),
        message=str(applets_payload.get("message", "")),
        all_clean=[str(v) for v in applets_payload.get("allClean", [])],
        files=[
            AppletFile(name=str(item.get("name", "")), lines=[str(v) for v in item.get("lines", [])])
            for item in applets_payload.get("files", [])
        ],
    )

    errors = [
        ErrorPayload(
            code=str(item.get("code", "")),
            message=str(item.get("message", "")),
            details=str(item.get("details", "")),
        )
        for item in payload.get("errors", [])
    ]

    apdu_steps = [
        ApduStep(
            command=str(item.get("command", "")),
            expected_status_words=[str(value) for value in item.get("expectedStatusWords", [])],
            expected_status_expression=str(item.get("expectedStatusExpression", "")),
            source_line=int(item.get("sourceLine", 0)),
        )
        for item in payload.get("apduSteps", [])
    ]

    return ParseResult(
        schema_version=int(payload["schemaVersion"]),
        parser_version=str(payload.get("parserVersion", "")),
        success=bool(payload.get("success", False)),
        status=str(payload.get("status", "")),
        message=str(payload.get("message", "")),
        generated_at=str(payload.get("generatedAt", "")),
        source_file=Path(str(payload.get("sourceFile", ""))),
        source_file_name=str(payload.get("sourceFileName", "")),
        detected_parser=DetectedParser(
            parser_id=str(detected.get("id", "")),
            display_name=str(detected.get("displayName", "")),
            supported=bool(detected.get("supported", False)),
        ),
        summary=ParserSummary(
            apdu_count=int(summary.get("apduCount", 0)),
            analysis_event_count=int(summary.get("analysisEventCount", 0)),
            applet_count=int(summary.get("appletCount", 0)),
            warning_count=int(summary.get("warningCount", 0)),
            exit_code=int(summary.get("exitCode", 0)),
        ),
        apdus=apdus,
        events=events,
        analysis=analysis,
        applets=applets,
        warnings=[str(v) for v in payload.get("warnings", [])],
        errors=errors,
        output_files=OutputFiles(
            json=Path(str(output_files["json"])) if output_files.get("json") else None,
            artifacts_dir=Path(str(output_files["artifactsDir"])) if output_files.get("artifactsDir") else None,
            apdu_text=Path(str(output_files["apduText"])) if output_files.get("apduText") else None,
            analysis_text=Path(str(output_files["analysisText"])) if output_files.get("analysisText") else None,
            errors_text=Path(str(output_files["errorsText"])) if output_files.get("errorsText") else None,
            legacy_result_json=Path(str(output_files["legacyResultJson"])) if output_files.get("legacyResultJson") else None,
            stderr_log=str(output_files.get("stderrLog", "")),
            java_text=Path(str(output_files["javaText"])) if output_files.get("javaText") else None,
        ),
        raw=payload,
        apdu_steps=apdu_steps,
        generated_java=str(payload.get("generatedJava", "")),
        generated_java_class_name=str(payload.get("generatedJavaClassName", "")),
    )
