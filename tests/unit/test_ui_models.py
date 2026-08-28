from __future__ import annotations

from pathlib import Path

from apdu_parser.core.models import (
    AnalysisEvent,
    ApduRow,
    AppletPayload,
    DetectedParser,
    FilterMode,
    ImportedLogItem,
    LogStatus,
    OutputFiles,
    ParseResult,
    ParserSummary,
)
from apdu_parser.ui.widgets.log_table import ImportedLogsTableModel
from apdu_parser.ui.widgets.result_tabs import JavaSnippetDialog, ResultTabs


def test_logs_table_model_state(qapp):
    model = ImportedLogsTableModel()
    item = ImportedLogItem(item_id="1", source_path=Path("C:/logs/sample.log"), status=LogStatus.PENDING)
    model.set_items([item])
    assert model.rowCount() == 1
    assert model.data(model.index(0, 0)) == "sample.log"
    assert model.data(model.index(0, 2)) == "Pending"


def test_result_tabs_empty_and_status_states(qapp):
    tabs = ResultTabs()
    tabs.show_empty_state()
    assert tabs.analysis_stack.currentIndex() == 1
    assert tabs.analysis_state._state_title.text() == "No log selected yet"
    assert tabs.apdu_model.rowCount() == 0

    tabs.show_status_state(
        "Unsupported format",
        "No internal parser matched this log file.",
        error_summary="Unsupported format",
        preferred_tab="analysis",
    )
    assert tabs.analysis_state._state_title.text() == "Unsupported format"
    assert tabs.error_summary.text() == "Unsupported format"
    assert tabs.tabs.currentIndex() == 1

    tabs.show_status_state(
        "Parser failure",
        "The Java parser did not complete successfully for this log.",
        error_summary="Failed (3)",
        preferred_tab="errors",
    )
    assert tabs.tabs.currentIndex() == 3


def test_result_tabs_render_completed_result(qapp):
    tabs = ResultTabs()
    result = ParseResult(
        schema_version=1,
        parser_version="1.0.0",
        success=True,
        status="success",
        message="Completed",
        generated_at="2026-07-09T00:00:00Z",
        source_file=Path("C:/logs/sample.log"),
        source_file_name="sample.log",
        detected_parser=DetectedParser(parser_id="pcsc", display_name="pcsc", supported=True),
        summary=ParserSummary(apdu_count=1, analysis_event_count=1, applet_count=0, warning_count=0, exit_code=0),
        apdus=[],
        events=[],
        analysis=[
            AnalysisEvent(
                index=12,
                event_type="es10",
                title="ES10 / DisableProfile",
                message="Tag BF32",
                severity="INFO",
                status_word="9000",
                tag="BF32",
                source_line=48,
            )
        ],
        applets=AppletPayload(status="no_applets", message="No applets found.", all_clean=[], files=[]),
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
        generated_java='response = test.sendApdu("00 A4 04 00 00");\n',
    )

    tabs.set_result("sample.log", result)

    assert "DisableProfile" in tabs.analysis_browser.toPlainText()
    assert tabs.error_summary.text() == "No parser errors."
    assert tabs.generate_java_btn.isEnabled()


def test_result_tabs_show_reset_only_in_all_filter(qapp):
    tabs = ResultTabs()
    reset = ApduRow(
        index=None,
        command="RESET",
        response="3B9F96803FC7838031E073F62113674B0758E0240200A1",
        command_name="RESET",
        headline="RESET",
        status_word="",
        severity="INFO",
        tag="",
        source_line=7,
        event_sequence=1,
        event_type="RESET",
        reset_type="COLD_RESET",
        atr="3B9F96803FC7838031E073F62113674B0758E0240200A1",
    )
    tabs.apdu_model.set_rows([reset])

    tabs.set_filter_mode(FilterMode.ALL)
    assert tabs.apdu_proxy.rowCount() == 1
    tabs.search_input.setText("cold reset")
    assert tabs.apdu_proxy.rowCount() == 1
    tabs.search_input.clear()

    for mode in (FilterMode.ES10, FilterMode.FETCH_TR, FilterMode.LSI):
        tabs.set_filter_mode(mode)
        assert tabs.apdu_proxy.rowCount() == 0


def test_java_snippet_dialog_exports_only_generated_content(qapp, monkeypatch, tmp_path):
    content = (
        'response = test.sendApdu("00 A4 00 04 02 3F 00");\n'
        'if (response.checkSw("9000") == false) {numErrors += 1;}\n'
    )
    target = tmp_path / "generated.java"
    dialog = JavaSnippetDialog(content, "report.java")
    monkeypatch.setattr(
        "apdu_parser.ui.widgets.result_tabs.QFileDialog.getSaveFileName",
        lambda *args, **kwargs: (str(target), "Java Files (*.java)"),
    )

    dialog.export_java()

    assert target.read_text(encoding="utf-8") == content
