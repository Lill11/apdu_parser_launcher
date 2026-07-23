from __future__ import annotations

import time
from pathlib import Path

from PySide6.QtWidgets import QMessageBox

from apdu_parser.core.parser_management_models import ManagedParser, ManagedParserListResult, ParserActionResult, ParserTestResult, PluginValidationResult
from apdu_parser.ui.dialogs.manage_parsers_dialog import ManageParsersDialog


class FakeParserManagementService:
    def __init__(self) -> None:
        self.fail_remove_for: str | None = None
        self.list_delay = 0.0
        self.remove_delay = 0.0
        self.disable_delay = 0.0
        self.test_delay = 0.0
        self.parsers = [
            ManagedParser(
                name="Built-in Parser",
                parser_id="builtin",
                version="1.0.0",
                plugin_api_version=1,
                supported_extensions=[".log"],
                source_type="BUILT_IN",
                enabled=True,
                validation_status="COMPATIBLE",
                validation_message="Compatible",
                install_directory="",
                plugin_jar="",
                implementation_class="BuiltinParser",
                built_in=True,
                preserved_source_file="",
                original_source_path="",
                compile_log_path="",
                legacy_main_class="",
                legacy_command_pattern="",
                legacy_output_file_name="",
                last_compiled_at="",
                last_compilation_status="",
                last_compilation_message="",
                last_tested_at="",
                last_test_status="",
                last_test_message="",
                last_test_stderr="",
                last_validated_at="",
                installed_at="",
                priority=100,
            ),
            ManagedParser(
                name="Jar Parser",
                parser_id="jar_parser",
                version="1.2.0",
                plugin_api_version=1,
                supported_extensions=[".txt"],
                source_type="PLUGIN_JAR",
                enabled=True,
                validation_status="COMPATIBLE",
                validation_message="Compatible",
                install_directory="C:/plugins/jar_parser",
                plugin_jar="C:/plugins/jar_parser/plugin.jar",
                implementation_class="example.JarParser",
                built_in=False,
                preserved_source_file="",
                original_source_path="",
                compile_log_path="C:/plugins/jar_parser/compile.log",
                legacy_main_class="",
                legacy_command_pattern="",
                legacy_output_file_name="",
                last_compiled_at="",
                last_compilation_status="",
                last_compilation_message="",
                last_tested_at="",
                last_test_status="",
                last_test_message="",
                last_test_stderr="",
                last_validated_at="2026-07-10T00:00:00Z",
                installed_at="2026-07-10T00:00:00Z",
                priority=200,
            ),
            ManagedParser(
                name="Source Parser",
                parser_id="source_parser",
                version="1.0.0",
                plugin_api_version=1,
                supported_extensions=[".log"],
                source_type="JAVA_SOURCE",
                enabled=True,
                validation_status="COMPATIBLE",
                validation_message="Compatible",
                install_directory="C:/plugins/source_parser",
                plugin_jar="C:/plugins/source_parser/plugin.jar",
                implementation_class="example.SourceParser",
                built_in=False,
                preserved_source_file="C:/plugins/source_parser/source/SourceParser.java",
                original_source_path="C:/Users/junli/Desktop/SourceParser.java",
                compile_log_path="C:/plugins/source_parser/compile.log",
                legacy_main_class="",
                legacy_command_pattern="",
                legacy_output_file_name="",
                last_compiled_at="2026-07-15T00:00:00Z",
                last_compilation_status="SUCCESS",
                last_compilation_message="Compilation succeeded.",
                last_tested_at="",
                last_test_status="",
                last_test_message="",
                last_test_stderr="",
                last_validated_at="2026-07-15T00:00:00Z",
                installed_at="2026-07-15T00:00:00Z",
                priority=200,
            ),
        ]

    def list_parsers(self) -> ManagedParserListResult:
        if self.list_delay:
            time.sleep(self.list_delay)
        return ManagedParserListResult(success=True, message="ok", parsers=list(self.parsers))

    def validate_plugin(self, jar_path: Path) -> PluginValidationResult:
        return PluginValidationResult(True, "Compatible", "COMPATIBLE", str(jar_path), "", [], self.parsers[1])

    def install_plugin(self, jar_path: Path) -> ParserActionResult:
        return ParserActionResult(True, "Installed", self.parsers[1])

    def install_source(self, source_path: Path) -> ParserActionResult:
        return ParserActionResult(True, "Installed", self.parsers[1])

    def inspect_legacy_source(self, source_path: Path):
        class Result:
            success = True
            message = "ok"
            status = "COMPATIBLE"
            diagnostics = []
            package_name = ""
            public_class_name = "LegacyParser"
            main_class_name = "LegacyParser"
        return Result()

    def install_legacy_source(self, **kwargs) -> ParserActionResult:
        return ParserActionResult(True, "Installed", self.parsers[1], stdout="", stderr="", generated_output_path="", apdu_count=1)

    def enable_parser(self, parser_id: str) -> ParserActionResult:
        return ParserActionResult(True, "Enabled", self.parsers[1])

    def disable_parser(self, parser_id: str) -> ParserActionResult:
        if self.disable_delay:
            time.sleep(self.disable_delay)
        return ParserActionResult(True, "Disabled", self.parsers[1])

    def remove_plugin(self, parser_id: str) -> ParserActionResult:
        if self.remove_delay:
            time.sleep(self.remove_delay)
        if self.fail_remove_for == parser_id:
            return ParserActionResult(False, "The plugin files could not be deleted.")
        self.parsers = [parser for parser in self.parsers if parser.parser_id != parser_id]
        return ParserActionResult(True, "Removed", None)

    def recompile_parser(self, parser_id: str) -> ParserActionResult:
        return ParserActionResult(True, "Recompiled", self.parsers[2])

    def test_parser(self, parser_id: str, input_path: Path) -> ParserTestResult:
        if self.test_delay:
            time.sleep(self.test_delay)
        return ParserTestResult(True, "ok", self.parsers[1], True, 120, "Matched", "completed", 3, 0, 0, 12, 0, "", "", "", [], [])


class EmptyParserManagementService(FakeParserManagementService):
    def list_parsers(self) -> ManagedParserListResult:
        return ManagedParserListResult(success=True, message="ok", parsers=[])


class FailingParserManagementService(FakeParserManagementService):
    def list_parsers(self) -> ManagedParserListResult:
        raise RuntimeError("Inventory unavailable")


def _wait_until(qapp, predicate, timeout: float = 2.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        qapp.processEvents()
        if predicate():
            return
        time.sleep(0.01)
    raise AssertionError("Timed out waiting for dialog state")


def test_manage_parsers_dialog_opens_in_loading_state(qapp):
    dialog = ManageParsersDialog(FakeParserManagementService())
    dialog.show()
    qapp.processEvents()
    assert dialog.content_stack.currentWidget() is dialog.loading_page
    assert dialog.loading_label.isVisible()


def test_manage_parsers_dialog_disables_remove_for_builtins(qapp):
    dialog = ManageParsersDialog(FakeParserManagementService())
    dialog.show()
    _wait_until(qapp, lambda: dialog.content_stack.currentWidget() is dialog.content_page)
    dialog.table.selectRow(0)
    dialog._selection_changed()
    assert not dialog.remove_button.isVisible()
    assert dialog.disable_button.isVisible()
    assert "cannot be removed" in dialog.details.toPlainText().lower()


def test_manage_parsers_dialog_enables_remove_for_user_plugins(qapp):
    dialog = ManageParsersDialog(FakeParserManagementService())
    dialog.show()
    _wait_until(qapp, lambda: dialog.content_stack.currentWidget() is dialog.content_page)
    dialog.table.selectRow(1)
    dialog._selection_changed()
    assert dialog.remove_button.isVisible()
    assert not dialog.recompile_button.isVisible()
    assert "Jar Parser" in dialog.details.toPlainText()


def test_manage_parsers_dialog_enables_recompile_for_source_plugins(qapp):
    dialog = ManageParsersDialog(FakeParserManagementService())
    dialog.show()
    _wait_until(qapp, lambda: dialog.content_stack.currentWidget() is dialog.content_page)
    dialog.table.selectRow(2)
    dialog._selection_changed()
    assert dialog.recompile_button.isVisible()
    assert dialog.view_compile_errors_button.isVisible()
    assert "Compilation status" in dialog.details.toPlainText()


def test_manage_parsers_dialog_shows_empty_state(qapp):
    dialog = ManageParsersDialog(EmptyParserManagementService())
    dialog.show()
    _wait_until(qapp, lambda: dialog.content_stack.currentWidget() is dialog.empty_page)
    assert not dialog.loading_label.isVisible()


def test_manage_parsers_dialog_shows_error_state(qapp):
    dialog = ManageParsersDialog(FailingParserManagementService())
    dialog.show()
    _wait_until(qapp, lambda: dialog.content_stack.currentWidget() is dialog.error_page)
    assert "Inventory unavailable" in dialog.error_message.text()
    assert not dialog.loading_label.isVisible()


def test_manage_parsers_dialog_remove_updates_list_for_jar_plugin(qapp, monkeypatch):
    service = FakeParserManagementService()
    dialog = ManageParsersDialog(service)
    dialog.show()
    _wait_until(qapp, lambda: dialog.content_stack.currentWidget() is dialog.content_page)
    dialog.table.selectRow(1)
    dialog._selection_changed()
    monkeypatch.setattr(
        "apdu_parser.ui.dialogs.manage_parsers_dialog.QMessageBox.question",
        lambda *args, **kwargs: QMessageBox.StandardButton.Yes,
    )
    dialog._remove_selected()
    _wait_until(qapp, lambda: dialog.table.rowCount() == 2)
    assert all(parser.parser_id != "jar_parser" for parser in service.parsers)


def test_manage_parsers_dialog_remove_updates_list_for_java_source_plugin(qapp, monkeypatch):
    service = FakeParserManagementService()
    dialog = ManageParsersDialog(service)
    dialog.show()
    _wait_until(qapp, lambda: dialog.content_stack.currentWidget() is dialog.content_page)
    dialog.table.selectRow(2)
    dialog._selection_changed()
    monkeypatch.setattr(
        "apdu_parser.ui.dialogs.manage_parsers_dialog.QMessageBox.question",
        lambda *args, **kwargs: QMessageBox.StandardButton.Yes,
    )
    dialog._remove_selected()
    _wait_until(qapp, lambda: dialog.table.rowCount() == 2)
    assert all(parser.parser_id != "source_parser" for parser in service.parsers)


def test_manage_parsers_dialog_failed_removal_shows_error(qapp, monkeypatch):
    service = FakeParserManagementService()
    service.fail_remove_for = "jar_parser"
    dialog = ManageParsersDialog(service)
    dialog.show()
    _wait_until(qapp, lambda: dialog.content_stack.currentWidget() is dialog.content_page)
    dialog.table.selectRow(1)
    dialog._selection_changed()

    captured: dict[str, str] = {}
    monkeypatch.setattr(
        "apdu_parser.ui.dialogs.manage_parsers_dialog.QMessageBox.question",
        lambda *args, **kwargs: QMessageBox.StandardButton.Yes,
    )
    monkeypatch.setattr(
        "apdu_parser.ui.dialogs.manage_parsers_dialog.QMessageBox.warning",
        lambda _parent, title, message: captured.update({"title": title, "message": message}),
    )
    dialog._remove_selected()
    _wait_until(qapp, lambda: "message" in captured)
    assert "Could not remove parser 'Jar Parser' (jar_parser)." in captured["message"]
    assert dialog.table.rowCount() == 3


def test_manage_parsers_dialog_disable_shows_busy_immediately(qapp):
    service = FakeParserManagementService()
    service.disable_delay = 0.2
    dialog = ManageParsersDialog(service)
    dialog.show()
    _wait_until(qapp, lambda: dialog.content_stack.currentWidget() is dialog.content_page)
    dialog.table.selectRow(1)
    dialog._selection_changed()

    dialog._disable_selected()

    qapp.processEvents()
    assert dialog._busy is True
    assert dialog.operation_frame.isVisible()
    assert dialog.operation_status_label.text() == "Disabling parser..."
    assert dialog.disable_button.text() == "Disabling..."


def test_manage_parsers_dialog_test_shows_busy_immediately(qapp, monkeypatch, tmp_path):
    service = FakeParserManagementService()
    service.test_delay = 0.2
    dialog = ManageParsersDialog(service)
    dialog.show()
    _wait_until(qapp, lambda: dialog.content_stack.currentWidget() is dialog.content_page)
    dialog.table.selectRow(1)
    dialog._selection_changed()

    sample = tmp_path / "sample.log"
    sample.write_text("dummy", encoding="utf-8")
    monkeypatch.setattr(
        "apdu_parser.ui.dialogs.manage_parsers_dialog.QFileDialog.getOpenFileName",
        lambda *args, **kwargs: (str(sample), "Supported Logs (*.txt *.log *.html *.htm)"),
    )
    monkeypatch.setattr(
        "apdu_parser.ui.dialogs.manage_parsers_dialog.TextReportDialog.exec",
        lambda self: 0,
    )

    dialog._test_selected()

    qapp.processEvents()
    assert dialog._busy is True
    assert dialog.operation_frame.isVisible()
    assert dialog.operation_status_label.text() == "Testing parser..."
    assert dialog.test_button.text() == "Testing..."
