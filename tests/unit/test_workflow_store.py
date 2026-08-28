from __future__ import annotations

from pathlib import Path

from apdu_parser.core.models import (
    AppletPayload,
    DetectedParser,
    LogStatus,
    OutputFiles,
    ParseResult,
    ParserSummary,
)
from apdu_parser.core.workflow import WorkflowStore


def test_workflow_store_deduplicates_files(tmp_path):
    file_path = tmp_path / "sample.log"
    file_path.write_text("x", encoding="utf-8")
    store = WorkflowStore()
    created = store.add_files([file_path, file_path])
    assert len(created) == 1
    assert len(store.items) == 1


def make_result(*, exit_code: int, success: bool, status: str, parser_name: str) -> ParseResult:
    return ParseResult(
        schema_version=1,
        parser_version="1.0.0",
        success=success,
        status=status,
        message=status.title(),
        generated_at="2026-07-09T00:00:00Z",
        source_file=Path("C:/logs/sample.log"),
        source_file_name="sample.log",
        detected_parser=DetectedParser(parser_id=parser_name, display_name=parser_name, supported=success),
        summary=ParserSummary(
            apdu_count=1,
            analysis_event_count=0,
            applet_count=0,
            warning_count=0,
            exit_code=exit_code,
        ),
        apdus=[],
        events=[],
        analysis=[],
        applets=AppletPayload(status="not_applicable", message="", all_clean=[], files=[]),
        warnings=[],
        errors=[],
        output_files=OutputFiles(
            json=None,
            artifacts_dir=None,
            apdu_text=None,
            analysis_text=None,
            errors_text=None,
            legacy_result_json=None,
            stderr_log="",
        ),
        raw={},
    )


def test_workflow_store_maps_completed_result(tmp_path):
    path = tmp_path / "completed.log"
    path.write_text("x", encoding="utf-8")
    store = WorkflowStore()
    item = store.add_files([path])[0]

    updated = store.update_result(item.item_id, make_result(exit_code=0, success=True, status="success", parser_name="pcsc"))

    assert updated is not None
    assert updated.status == LogStatus.COMPLETED
    assert updated.detected_format == "pcsc"


def test_workflow_store_maps_unsupported_result(tmp_path):
    path = tmp_path / "unsupported.log"
    path.write_text("x", encoding="utf-8")
    store = WorkflowStore()
    item = store.add_files([path])[0]

    updated = store.update_result(item.item_id, make_result(exit_code=1, success=False, status="unsupported", parser_name="unsupported"))

    assert updated is not None
    assert updated.status == LogStatus.UNSUPPORTED


def test_workflow_store_maps_failed_result(tmp_path):
    path = tmp_path / "failed.log"
    path.write_text("x", encoding="utf-8")
    store = WorkflowStore()
    item = store.add_files([path])[0]

    updated = store.update_result(item.item_id, make_result(exit_code=3, success=False, status="parser_error", parser_name="pcsc"))

    assert updated is not None
    assert updated.status == LogStatus.FAILED
