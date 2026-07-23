from __future__ import annotations

import re
from collections.abc import Callable
from pathlib import Path

from PySide6.QtCore import QObject, QRunnable, QThreadPool, QTimer, Qt, Signal
from PySide6.QtGui import QGuiApplication
from PySide6.QtWidgets import (
    QComboBox,
    QDialog,
    QFileDialog,
    QFormLayout,
    QFrame,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMessageBox,
    QPushButton,
    QProgressBar,
    QSplitter,
    QStackedWidget,
    QTableWidget,
    QTableWidgetItem,
    QTextBrowser,
    QVBoxLayout,
    QWidget,
)

from apdu_parser.core.parser_management_models import ManagedParser, ManagedParserListResult
from apdu_parser.services.parser_management_service import ParserManagementService


class ParserListWorkerSignals(QObject):
    finished = Signal(object)
    failed = Signal(str, str)


class ParserListWorker(QRunnable):
    def __init__(self, parser_service: ParserManagementService) -> None:
        super().__init__()
        self.parser_service = parser_service
        self.signals = ParserListWorkerSignals()
        self.setAutoDelete(True)

    def run(self) -> None:
        try:
            result = self.parser_service.list_parsers()
            try:
                self.signals.finished.emit(result)
            except RuntimeError:
                return
        except Exception as exc:  # pragma: no cover - defensive safety net
            try:
                self.signals.failed.emit(str(exc), repr(exc))
            except RuntimeError:
                return


class CallableWorkerSignals(QObject):
    finished = Signal(object)
    failed = Signal(str, str)


class CallableWorker(QRunnable):
    def __init__(self, operation: Callable[[], object]) -> None:
        super().__init__()
        self._operation = operation
        self.signals = CallableWorkerSignals()
        self.setAutoDelete(True)

    def run(self) -> None:
        try:
            result = self._operation()
            try:
                self.signals.finished.emit(result)
            except RuntimeError:
                return
        except Exception as exc:  # pragma: no cover - defensive safety net
            try:
                self.signals.failed.emit(str(exc), repr(exc))
            except RuntimeError:
                return


class ManageParsersDialog(QDialog):
    headers = ["Name", "ID", "Source", "Status", "Enabled", "Version"]

    def __init__(self, parser_service: ParserManagementService, parent=None) -> None:
        super().__init__(parent)
        self.parser_service = parser_service
        self.thread_pool = QThreadPool(self)
        self.parsers: list[ManagedParser] = []
        self.selected_parser: ManagedParser | None = None
        self._active_workers: list[ParserListWorker] = []
        self._active_operation_workers: list[CallableWorker] = []
        self._loading_generation = 0
        self._busy = False
        self._last_error_details = ""
        self._pending_preferred_parser_id: str | None = None
        self._operation_generation = 0
        self._status_timer = QTimer(self)
        self._status_timer.setInterval(900)
        self._status_timer.timeout.connect(self._advance_operation_stage)
        self._operation_stage_messages: list[str] = []
        self._operation_stage_index = 0
        self._operation_cleanup: Callable[[], None] | None = None
        self._active_operation_name = ""

        self.setWindowTitle("Manage Parsers")
        self.resize(1160, 740)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 16, 16, 16)
        layout.setSpacing(12)

        header = QHBoxLayout()
        title_col = QVBoxLayout()
        title = QLabel("Manage Parsers")
        title.setObjectName("panelTitle")
        subtitle = QLabel(
            "Review built-in parsers, add trusted .jar plugins, install Java parser sources, or wrap older standalone Java extractors."
        )
        subtitle.setObjectName("secondaryText")
        subtitle.setWordWrap(True)
        self.loading_label = QLabel("Loading parser inventory...")
        self.loading_label.setObjectName("secondaryText")
        self.loading_label.hide()
        title_col.addWidget(title)
        title_col.addWidget(subtitle)
        title_col.addWidget(self.loading_label)
        header.addLayout(title_col, 1)

        self.refresh_button = QPushButton("Refresh")
        self.refresh_button.setObjectName("linkButton")
        self.add_legacy_button = QPushButton("Add Legacy Java Extractor")
        self.add_source_button = QPushButton("Add Java Parser")
        self.add_plugin_button = QPushButton("Add Parser Plugin")
        self.refresh_button.clicked.connect(self.reload)
        self.add_legacy_button.clicked.connect(self._add_legacy_source)
        self.add_source_button.clicked.connect(self._add_source)
        self.add_plugin_button.clicked.connect(self._add_plugin)
        header.addWidget(self.refresh_button)
        header.addWidget(self.add_legacy_button)
        header.addWidget(self.add_source_button)
        header.addWidget(self.add_plugin_button)
        layout.addLayout(header)

        self.operation_frame = QFrame()
        self.operation_frame.setObjectName("inlineStatusFrame")
        operation_layout = QHBoxLayout(self.operation_frame)
        operation_layout.setContentsMargins(12, 10, 12, 10)
        operation_layout.setSpacing(10)
        self.operation_spinner = QProgressBar()
        self.operation_spinner.setRange(0, 0)
        self.operation_spinner.setTextVisible(False)
        self.operation_spinner.setFixedWidth(120)
        self.operation_status_label = QLabel("")
        self.operation_status_label.setObjectName("secondaryText")
        self.operation_status_label.setWordWrap(True)
        operation_layout.addWidget(self.operation_spinner)
        operation_layout.addWidget(self.operation_status_label, 1)
        self.operation_frame.hide()
        layout.addWidget(self.operation_frame)

        self.content_stack = QStackedWidget()
        self.loading_page = self._build_loading_page()
        self.content_page = self._build_content_page()
        self.empty_page = self._build_empty_page()
        self.error_page = self._build_error_page()
        self.content_stack.addWidget(self.loading_page)
        self.content_stack.addWidget(self.content_page)
        self.content_stack.addWidget(self.empty_page)
        self.content_stack.addWidget(self.error_page)
        layout.addWidget(self.content_stack, 1)

        self._show_loading("Loading parser inventory...")
        QTimer.singleShot(0, lambda: self.reload(force=True))

    def _build_content_page(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        splitter = QSplitter(Qt.Orientation.Horizontal)
        splitter.setChildrenCollapsible(False)

        left = QWidget()
        left_layout = QVBoxLayout(left)
        left_layout.setContentsMargins(0, 0, 0, 0)
        left_layout.setSpacing(8)
        self.table = QTableWidget(0, len(self.headers))
        self.table.setHorizontalHeaderLabels(self.headers)
        self.table.verticalHeader().setVisible(False)
        self.table.verticalHeader().setDefaultSectionSize(40)
        self.table.setSelectionBehavior(QTableWidget.SelectionBehavior.SelectRows)
        self.table.setSelectionMode(QTableWidget.SelectionMode.SingleSelection)
        self.table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        self.table.setAlternatingRowColors(False)
        self.table.setShowGrid(False)
        self.table.setWordWrap(False)
        self.table.horizontalHeader().setStretchLastSection(False)
        self.table.horizontalHeader().setSectionResizeMode(0, self.table.horizontalHeader().ResizeMode.Stretch)
        self.table.horizontalHeader().setSectionResizeMode(1, self.table.horizontalHeader().ResizeMode.ResizeToContents)
        self.table.horizontalHeader().setSectionResizeMode(2, self.table.horizontalHeader().ResizeMode.ResizeToContents)
        self.table.horizontalHeader().setSectionResizeMode(3, self.table.horizontalHeader().ResizeMode.ResizeToContents)
        self.table.horizontalHeader().setSectionResizeMode(4, self.table.horizontalHeader().ResizeMode.ResizeToContents)
        self.table.horizontalHeader().setSectionResizeMode(5, self.table.horizontalHeader().ResizeMode.ResizeToContents)
        self.table.itemSelectionChanged.connect(self._selection_changed)
        left_layout.addWidget(self.table)

        right = QWidget()
        right_layout = QVBoxLayout(right)
        right_layout.setContentsMargins(0, 0, 0, 0)
        right_layout.setSpacing(8)

        details_title = QLabel("Details")
        details_title.setObjectName("panelTitle")
        right_layout.addWidget(details_title)

        self.details = QTextBrowser()
        self.details.setOpenExternalLinks(False)
        self.details.setPlainText("Loading parsers...")
        right_layout.addWidget(self.details, 1)

        actions = QHBoxLayout()
        actions.setSpacing(8)
        self.enable_button = QPushButton("Enable")
        self.disable_button = QPushButton("Disable")
        self.remove_button = QPushButton("Remove")
        self.recompile_button = QPushButton("Recompile")
        self.view_compile_errors_button = QPushButton("View Compilation Errors")
        self.test_button = QPushButton("Test Parser")
        self.copy_button = QPushButton("Copy Details")
        self.enable_button.clicked.connect(self._enable_selected)
        self.disable_button.clicked.connect(self._disable_selected)
        self.remove_button.clicked.connect(self._remove_selected)
        self.recompile_button.clicked.connect(self._recompile_selected)
        self.view_compile_errors_button.clicked.connect(self._view_compile_errors)
        self.test_button.clicked.connect(self._test_selected)
        self.copy_button.clicked.connect(lambda: QGuiApplication.clipboard().setText(self.details.toPlainText()))
        for button in (
            self.enable_button,
            self.disable_button,
            self.remove_button,
            self.recompile_button,
            self.view_compile_errors_button,
            self.test_button,
            self.copy_button,
        ):
            actions.addWidget(button)
        actions.addStretch(1)
        right_layout.addLayout(actions)

        splitter.addWidget(left)
        splitter.addWidget(right)
        splitter.setSizes([740, 420])
        layout.addWidget(splitter)
        return page

    def _build_loading_page(self) -> QWidget:
        page, card_layout = self._build_state_shell()
        icon = QLabel("...")
        icon.setObjectName("stateIcon")
        title = QLabel("Loading parsers...")
        title.setObjectName("stateTitle")
        message = QLabel("Fetching parser inventory and plugin status. Please wait.")
        message.setObjectName("stateMessage")
        message.setWordWrap(True)
        progress = QProgressBar()
        progress.setRange(0, 0)
        progress.setTextVisible(False)
        progress.setMaximumWidth(280)

        card_layout.addWidget(icon, 0, Qt.AlignmentFlag.AlignHCenter)
        card_layout.addWidget(title, 0, Qt.AlignmentFlag.AlignHCenter)
        card_layout.addWidget(message, 0, Qt.AlignmentFlag.AlignHCenter)
        card_layout.addWidget(progress, 0, Qt.AlignmentFlag.AlignHCenter)
        return page

    def _build_empty_page(self) -> QWidget:
        page, card_layout = self._build_state_shell()
        icon = QLabel("+")
        icon.setObjectName("stateIcon")
        title = QLabel("No parsers found")
        title.setObjectName("stateTitle")
        message = QLabel("No built-in or installed parsers were returned.")
        message.setObjectName("stateMessage")
        message.setWordWrap(True)
        hint = QLabel("Try Refresh, add a parser plugin, add a Java parser, or wrap a legacy extractor.")
        hint.setObjectName("stateHint")
        hint.setWordWrap(True)
        retry = QPushButton("Refresh")
        retry.clicked.connect(self.reload)

        card_layout.addWidget(icon, 0, Qt.AlignmentFlag.AlignHCenter)
        card_layout.addWidget(title, 0, Qt.AlignmentFlag.AlignHCenter)
        card_layout.addWidget(message, 0, Qt.AlignmentFlag.AlignHCenter)
        card_layout.addWidget(hint, 0, Qt.AlignmentFlag.AlignHCenter)
        card_layout.addWidget(retry, 0, Qt.AlignmentFlag.AlignHCenter)
        return page

    def _build_error_page(self) -> QWidget:
        page, card_layout = self._build_state_shell()
        icon = QLabel("!")
        icon.setObjectName("stateIcon")
        self.error_title = QLabel("Unable to load parsers")
        self.error_title.setObjectName("stateTitle")
        self.error_message = QLabel("The parser inventory could not be loaded.")
        self.error_message.setObjectName("stateMessage")
        self.error_message.setWordWrap(True)
        hint = QLabel("Retry the load or copy details for troubleshooting.")
        hint.setObjectName("stateHint")
        hint.setWordWrap(True)
        actions = QHBoxLayout()
        actions.setSpacing(8)
        retry = QPushButton("Retry")
        retry.clicked.connect(self.reload)
        copy = QPushButton("Copy Details")
        copy.clicked.connect(self._copy_error_details)
        actions.addWidget(retry)
        actions.addWidget(copy)

        card_layout.addWidget(icon, 0, Qt.AlignmentFlag.AlignHCenter)
        card_layout.addWidget(self.error_title, 0, Qt.AlignmentFlag.AlignHCenter)
        card_layout.addWidget(self.error_message, 0, Qt.AlignmentFlag.AlignHCenter)
        card_layout.addWidget(hint, 0, Qt.AlignmentFlag.AlignHCenter)
        card_layout.addLayout(actions)
        return page

    def _build_state_shell(self) -> tuple[QWidget, QVBoxLayout]:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.addStretch(1)
        card = QFrame()
        card.setObjectName("stateCard")
        card_layout = QVBoxLayout(card)
        card_layout.setContentsMargins(28, 28, 28, 28)
        card_layout.setSpacing(12)
        layout.addWidget(card, 0, Qt.AlignmentFlag.AlignHCenter)
        layout.addStretch(1)
        return page, card_layout

    def reload(self, preferred_parser_id: str | None = None, *, force: bool = False) -> None:
        if self._busy and not force:
            return
        self._pending_preferred_parser_id = preferred_parser_id
        self._loading_generation += 1
        generation = self._loading_generation
        self._show_loading("Loading parser inventory...")
        worker = ParserListWorker(self.parser_service)
        self._active_workers.append(worker)
        worker.signals.finished.connect(lambda result, gen=generation, current=worker: self._on_reload_finished(gen, result, current))
        worker.signals.failed.connect(
            lambda message, details, gen=generation, current=worker: self._on_reload_failed(gen, message, details, current)
        )
        QTimer.singleShot(0, lambda current=worker: self.thread_pool.start(current))

    def _on_reload_finished(self, generation: int, result: ManagedParserListResult, worker: ParserListWorker) -> None:
        self._discard_worker(worker)
        if generation != self._loading_generation:
            return
        self._set_busy(False)
        self.loading_label.hide()
        if not result.success:
            self._show_error(result.message or "The parser inventory request did not succeed.", result.message)
            return
        self._populate_table(result.parsers)
        if not result.parsers:
            self._show_empty()
            return
        self._show_loaded()

    def _on_reload_failed(self, generation: int, message: str, details: str, worker: ParserListWorker) -> None:
        self._discard_worker(worker)
        if generation != self._loading_generation:
            return
        self._set_busy(False)
        self.loading_label.hide()
        self._show_error(message or "Unable to load parsers.", details or message)

    def _discard_worker(self, worker: ParserListWorker) -> None:
        try:
            self._active_workers.remove(worker)
        except ValueError:
            pass

    def _discard_operation_worker(self, worker: CallableWorker) -> None:
        try:
            self._active_operation_workers.remove(worker)
        except ValueError:
            pass

    def _populate_table(self, parsers: list[ManagedParser]) -> None:
        preferred_parser_id = self._pending_preferred_parser_id
        self.parsers = list(parsers)
        self.selected_parser = None
        self.table.clearContents()
        self.table.setRowCount(len(self.parsers))
        for row, parser in enumerate(self.parsers):
            values = [
                parser.name,
                parser.parser_id,
                parser.source_type.replace("_", " ").title(),
                parser.validation_status.replace("_", " ").title(),
                "Enabled" if parser.enabled else "Disabled",
                parser.version,
            ]
            for column, value in enumerate(values):
                item = QTableWidgetItem(value)
                item.setToolTip(value)
                if column == 0:
                    item.setData(Qt.ItemDataRole.UserRole, parser.parser_id)
                    item.setToolTip(f"{parser.name}\n{parser.parser_id}")
                self.table.setItem(row, column, item)

        if self.parsers:
            selected_row = 0
            if preferred_parser_id:
                for row, parser in enumerate(self.parsers):
                    if parser.parser_id == preferred_parser_id:
                        selected_row = row
                        break
            self.table.selectRow(selected_row)
            self.table.setCurrentCell(selected_row, 0)
            self._selection_changed()
        else:
            self.details.setPlainText("No parsers available.")
            self._refresh_buttons()

    def _selection_changed(self) -> None:
        row = self.table.currentRow()
        self.selected_parser = self.parsers[row] if 0 <= row < len(self.parsers) else None
        self._render_details()
        self._refresh_buttons()

    def _render_details(self) -> None:
        parser = self.selected_parser
        if parser is None:
            self.details.setPlainText("Select a parser to review details.")
            return

        summary_bits = [
            "Built-in parser - cannot be removed" if parser.built_in else parser.source_type.replace("_", " ").title(),
            "Enabled" if parser.enabled else "Disabled",
            parser.validation_status.replace("_", " ").title(),
        ]
        sections = [
            f"<div class='analysisTitle'>{parser.name}</div>",
            f"<div class='analysisMeta'>{' - '.join(summary_bits)}</div>",
            "<div class='analysisMessage'>",
            f"<b>ID:</b> {parser.parser_id}<br/>",
            f"<b>Version:</b> {parser.version}<br/>",
            f"<b>API:</b> {parser.plugin_api_version}<br/>",
            f"<b>Extensions:</b> {', '.join(parser.supported_extensions) or '-'}<br/>",
        ]
        if parser.validation_message:
            sections.append(f"<b>Validation message:</b> {parser.validation_message}<br/>")
        if parser.plugin_jar:
            sections.append(f"<b>Plugin JAR:</b> {parser.plugin_jar}<br/>")
        if parser.preserved_source_file:
            sections.append(f"<b>Preserved source:</b> {parser.preserved_source_file}<br/>")
        if parser.original_source_path:
            sections.append(f"<b>Original source path:</b> {parser.original_source_path}<br/>")
        if parser.legacy_main_class:
            sections.append(f"<b>Main class:</b> {parser.legacy_main_class}<br/>")
        if parser.legacy_command_pattern:
            sections.append(f"<b>Command pattern:</b> {parser.legacy_command_pattern}<br/>")
        if parser.legacy_output_file_name:
            sections.append(f"<b>Output filename:</b> {parser.legacy_output_file_name}<br/>")
        if parser.compile_log_path:
            sections.append(f"<b>Compile log:</b> {parser.compile_log_path}<br/>")
        if parser.last_compilation_status:
            sections.append(f"<b>Compilation status:</b> {parser.last_compilation_status}<br/>")
        if parser.last_compilation_message:
            sections.append(f"<b>Compilation message:</b> {parser.last_compilation_message}<br/>")
        if parser.last_compiled_at:
            sections.append(f"<b>Last compiled:</b> {parser.last_compiled_at}<br/>")
        if parser.last_test_status:
            sections.append(f"<b>Last test status:</b> {parser.last_test_status}<br/>")
        if parser.last_test_message:
            sections.append(f"<b>Last test message:</b> {parser.last_test_message}<br/>")
        if parser.last_tested_at:
            sections.append(f"<b>Last tested:</b> {parser.last_tested_at}<br/>")
        if parser.last_test_stderr:
            sections.append(f"<b>Last stderr:</b> {parser.last_test_stderr}<br/>")
        if parser.last_validated_at:
            sections.append(f"<b>Last validated:</b> {parser.last_validated_at}<br/>")
        if parser.installed_at:
            sections.append(f"<b>Installed at:</b> {parser.installed_at}<br/>")
        if parser.install_directory:
            sections.append(f"<b>Install directory:</b> {parser.install_directory}<br/>")
        if parser.implementation_class:
            sections.append(f"<b>Implementation:</b> {parser.implementation_class}<br/>")
        sections.append("</div>")
        self.details.setHtml("".join(sections))

    def _refresh_buttons(self) -> None:
        parser = self.selected_parser
        has_selection = parser is not None and not self._busy
        supports_recompile = bool(has_selection and parser and parser.source_type in {"JAVA_SOURCE", "LEGACY_JAVA_EXTRACTOR"})

        self.enable_button.setVisible(bool(has_selection and parser and not parser.enabled))
        self.disable_button.setVisible(bool(has_selection and parser and parser.enabled))
        self.remove_button.setVisible(bool(has_selection and parser and not parser.built_in))
        self.remove_button.setToolTip("" if not parser or not parser.built_in else "Built-in parsers cannot be removed.")
        self.recompile_button.setVisible(supports_recompile)
        self.view_compile_errors_button.setVisible(bool(supports_recompile and parser and parser.compile_log_path))
        self.test_button.setVisible(bool(has_selection))
        self.copy_button.setVisible(bool(has_selection))

        self.enable_button.setEnabled(bool(has_selection and parser and not parser.enabled))
        self.disable_button.setEnabled(bool(has_selection and parser and parser.enabled))
        self.remove_button.setEnabled(bool(has_selection and parser and not parser.built_in))
        self.recompile_button.setEnabled(supports_recompile)
        self.view_compile_errors_button.setEnabled(bool(supports_recompile and parser and parser.compile_log_path))
        self.test_button.setEnabled(bool(has_selection and parser and parser.enabled))
        self.copy_button.setEnabled(bool(has_selection))

    def _show_loading(self, message: str) -> None:
        self.loading_label.setText(message)
        self.loading_label.show()
        self.content_stack.setCurrentWidget(self.loading_page)
        self._set_busy(True)

    def _show_loaded(self) -> None:
        self.content_stack.setCurrentWidget(self.content_page)
        self._refresh_buttons()

    def _show_empty(self) -> None:
        self.selected_parser = None
        self.content_stack.setCurrentWidget(self.empty_page)
        self._refresh_buttons()

    def _show_error(self, message: str, details: str = "") -> None:
        self._last_error_details = details or message
        self.error_message.setText(message or "The parser inventory could not be loaded.")
        self.content_stack.setCurrentWidget(self.error_page)
        self.selected_parser = None
        self._refresh_buttons()

    def _set_busy(self, busy: bool) -> None:
        self._busy = busy
        if busy:
            self.setCursor(Qt.CursorShape.WaitCursor)
        else:
            self.unsetCursor()

        for widget in (
            self.refresh_button,
            self.add_legacy_button,
            self.add_source_button,
            self.add_plugin_button,
            self.table,
        ):
            widget.setEnabled(not busy)
        self._refresh_buttons()

    def _begin_operation(
        self,
        name: str,
        initial_message: str,
        *,
        button_overrides: dict[QPushButton, str] | None = None,
        stage_messages: list[str] | None = None,
    ) -> int:
        if self._busy:
            return -1
        self._operation_generation += 1
        generation = self._operation_generation
        self._active_operation_name = name
        self._set_busy(True)
        self.operation_status_label.setText(initial_message)
        self.operation_frame.show()
        self._operation_stage_messages = list(stage_messages or [])
        self._operation_stage_index = 0
        if self._operation_stage_messages:
            self._status_timer.start()
        if button_overrides:
            self._operation_cleanup = self._build_button_cleanup(button_overrides)
            for button, text in button_overrides.items():
                button.setText(text)
                button.setEnabled(False)
        else:
            self._operation_cleanup = None
        return generation

    def _build_button_cleanup(self, button_overrides: dict[QPushButton, str]) -> Callable[[], None]:
        originals = {button: button.text() for button in button_overrides}

        def cleanup() -> None:
            for button, text in originals.items():
                button.setText(text)

        return cleanup

    def _advance_operation_stage(self) -> None:
        if not self._operation_stage_messages:
            return
        index = min(self._operation_stage_index, len(self._operation_stage_messages) - 1)
        self.operation_status_label.setText(self._operation_stage_messages[index])
        if self._operation_stage_index < len(self._operation_stage_messages) - 1:
            self._operation_stage_index += 1

    def _complete_operation(self, generation: int, message: str = "") -> bool:
        if generation != self._operation_generation:
            return False
        self._status_timer.stop()
        self._operation_stage_messages = []
        if self._operation_cleanup:
            self._operation_cleanup()
            self._operation_cleanup = None
        self._set_busy(False)
        self._active_operation_name = ""
        if message:
            self.operation_status_label.setText(message)
            self.operation_frame.show()
        else:
            self.operation_frame.hide()
        return True

    def _run_operation(
        self,
        *,
        name: str,
        initial_message: str,
        operation: Callable[[], object],
        on_success: Callable[[object], None],
        on_failure: Callable[[str, str], None],
        button_overrides: dict[QPushButton, str] | None = None,
        stage_messages: list[str] | None = None,
    ) -> None:
        generation = self._begin_operation(
            name,
            initial_message,
            button_overrides=button_overrides,
            stage_messages=stage_messages,
        )
        if generation < 0:
            return
        worker = CallableWorker(operation)
        self._active_operation_workers.append(worker)
        worker.signals.finished.connect(
            lambda result, gen=generation, current=worker: self._finish_operation_success(gen, result, on_success, current)
        )
        worker.signals.failed.connect(
            lambda message, details, gen=generation, current=worker: self._finish_operation_failure(gen, message, details, on_failure, current)
        )
        QTimer.singleShot(0, lambda current=worker: self.thread_pool.start(current))

    def _finish_operation_success(
        self,
        generation: int,
        result: object,
        on_success: Callable[[object], None],
        worker: CallableWorker,
    ) -> None:
        self._discard_operation_worker(worker)
        if not self._complete_operation(generation):
            return
        on_success(result)

    def _finish_operation_failure(
        self,
        generation: int,
        message: str,
        details: str,
        on_failure: Callable[[str, str], None],
        worker: CallableWorker,
    ) -> None:
        self._discard_operation_worker(worker)
        if not self._complete_operation(generation):
            return
        on_failure(message, details)

    def _copy_error_details(self) -> None:
        QGuiApplication.clipboard().setText(self._last_error_details or self.error_message.text())

    def _add_source(self) -> None:
        if self._busy:
            return
        file_name, _ = QFileDialog.getOpenFileName(
            self,
            "Add Java Parser",
            str(Path.home()),
            "Java Source (*.java);;All Files (*.*)",
        )
        if not file_name:
            return
        source_path = Path(file_name)
        self._run_operation(
            name="add-source",
            initial_message="Installing Java parser...",
            operation=lambda: self.parser_service.install_source(source_path),
            on_success=self._handle_add_source_result,
            on_failure=lambda message, _details: QMessageBox.critical(self, "Add Java Parser", message),
            stage_messages=["Inspecting Java source...", "Compiling parser...", "Validating plugin...", "Refreshing parser list..."],
        )

    def _handle_add_source_result(self, result: object) -> None:
        assert isinstance(result, object)
        action = result
        if not action.success:
            details = action.compile_log or "\n".join(action.diagnostics) or action.message
            self._show_report("Add Java Parser", action.message, details)
            return
        self.operation_status_label.setText("Parser installed successfully.")
        self.reload(action.parser.parser_id if action.parser else None)

    def _add_legacy_source(self) -> None:
        if self._busy:
            return
        dialog = AddLegacyJavaExtractorDialog(self.parser_service, self)
        if dialog.exec() != QDialog.DialogCode.Accepted:
            return
        request = dialog.completed_request()
        if request is None:
            return
        self.operation_status_label.setText("Legacy extractor installed successfully.")
        self.operation_frame.show()
        self.reload(request.get("parser_id"))

    def _add_plugin(self) -> None:
        if self._busy:
            return
        file_name, _ = QFileDialog.getOpenFileName(
            self,
            "Add Parser Plugin",
            str(Path.home()),
            "Java Archives (*.jar);;All Files (*.*)",
        )
        if not file_name:
            return
        jar_path = Path(file_name)

        def install_plugin_flow():
            validation = self.parser_service.validate_plugin(jar_path)
            if not validation.success:
                return ("validation_failed", validation)
            return ("installed", self.parser_service.install_plugin(jar_path))

        self._run_operation(
            name="add-plugin",
            initial_message="Installing parser plugin...",
            operation=install_plugin_flow,
            on_success=self._handle_add_plugin_result,
            on_failure=lambda message, _details: QMessageBox.critical(self, "Add Parser Plugin", message),
            stage_messages=["Validating plugin...", "Installing plugin...", "Refreshing parser list..."],
        )

    def _handle_add_plugin_result(self, result: object) -> None:
        mode, payload = result
        if mode == "validation_failed":
            details = "\n".join(payload.diagnostics) if payload.diagnostics else payload.message
            self._show_report("Add Parser Plugin", payload.message, details)
            return
        if not payload.success:
            self._show_report("Add Parser Plugin", payload.message, payload.message)
            return
        self.operation_status_label.setText("Parser plugin installed successfully.")
        self.operation_frame.show()
        self.reload(payload.parser.parser_id if payload.parser else None)

    def _enable_selected(self) -> None:
        self._set_enabled(True)

    def _disable_selected(self) -> None:
        self._set_enabled(False)

    def _set_enabled(self, enabled: bool) -> None:
        parser = self.selected_parser
        if parser is None:
            return
        verb = "Enabling parser..." if enabled else "Disabling parser..."
        self._run_operation(
            name="set-enabled",
            initial_message=verb,
            operation=lambda: self.parser_service.enable_parser(parser.parser_id) if enabled else self.parser_service.disable_parser(parser.parser_id),
            on_success=lambda result: self._handle_set_enabled_result(parser.parser_id, enabled, result),
            on_failure=lambda message, _details: QMessageBox.critical(self, "Manage Parsers", message),
            button_overrides={(self.enable_button if enabled else self.disable_button): ("Enabling..." if enabled else "Disabling...")},
            stage_messages=[verb, "Refreshing parser list..."],
        )

    def _handle_set_enabled_result(self, parser_id: str, enabled: bool, result: object) -> None:
        if not result.success:
            self._show_report("Manage Parsers", result.message, result.message)
            return
        self.operation_status_label.setText("Parser enabled." if enabled else "Parser disabled.")
        self.operation_frame.show()
        self.reload(parser_id)

    def _remove_selected(self) -> None:
        parser = self.selected_parser
        if parser is None:
            return
        if parser.built_in:
            QMessageBox.information(self, "Remove Parser", "Built-in parsers cannot be removed.")
            return
        answer = QMessageBox.question(
            self,
            "Remove Parser",
            (
                f"Remove parser '{parser.name}' ({parser.parser_id})?\n\n"
                "This removes only the user-installed plugin files for this parser from the user plugin directory. "
                "Built-in parsers and other installed plugins will not be affected."
            ),
        )
        if answer != QMessageBox.StandardButton.Yes:
            return
        self._run_operation(
            name="remove-parser",
            initial_message=f"Removing parser '{parser.name}'...",
            operation=lambda: self.parser_service.remove_plugin(parser.parser_id),
            on_success=lambda result: self._handle_remove_result(parser, result),
            on_failure=lambda message, _details: QMessageBox.critical(
                self,
                "Remove Parser",
                f"Could not remove parser '{parser.name}' ({parser.parser_id}).\n\n{message}",
            ),
            button_overrides={self.remove_button: "Removing..."},
            stage_messages=["Removing plugin files...", "Refreshing parser list..."],
        )

    def _handle_remove_result(self, parser: ManagedParser, result: object) -> None:
        if not result.success:
            QMessageBox.warning(
                self,
                "Remove Parser",
                f"Could not remove parser '{parser.name}' ({parser.parser_id}).\n\n{result.message}",
            )
            return
        self.operation_status_label.setText("Parser removed.")
        self.operation_frame.show()
        self.reload()

    def _recompile_selected(self) -> None:
        parser = self.selected_parser
        if parser is None or parser.source_type not in {"JAVA_SOURCE", "LEGACY_JAVA_EXTRACTOR"}:
            return
        self._run_operation(
            name="recompile-parser",
            initial_message="Recompiling parser...",
            operation=lambda: self.parser_service.recompile_parser(parser.parser_id),
            on_success=lambda result: self._handle_recompile_result(parser.parser_id, result),
            on_failure=lambda message, _details: QMessageBox.critical(self, "Recompile Parser", message),
            button_overrides={self.recompile_button: "Recompiling..."},
            stage_messages=["Preparing sources...", "Compiling parser...", "Validating plugin...", "Refreshing parser list..."],
        )

    def _handle_recompile_result(self, parser_id: str, result: object) -> None:
        self.reload(parser_id)
        if result.success:
            self.operation_status_label.setText(result.message or "Parser recompiled successfully.")
            self.operation_frame.show()
            return
        details = result.compile_log or "\n".join(result.diagnostics) or result.message
        self._show_report("Recompile Parser", result.message, details)

    def _view_compile_errors(self) -> None:
        parser = self.selected_parser
        if parser is None or not parser.compile_log_path:
            return
        compile_log_path = Path(parser.compile_log_path)
        if not compile_log_path.exists():
            QMessageBox.information(self, "Compilation Log", "No compilation log is available yet.")
            return
        content = compile_log_path.read_text(encoding="utf-8", errors="replace").strip()
        self._show_report("Compilation Log", parser.name, content or "Compilation log is empty.")

    def _show_report(self, title: str, heading: str, content: str) -> None:
        dialog = TextReportDialog(title, heading, content, self)
        dialog.exec()

    def _test_selected(self) -> None:
        parser = self.selected_parser
        if parser is None:
            return
        file_name, _ = QFileDialog.getOpenFileName(
            self,
            "Test Parser",
            str(Path.home()),
            "Supported Logs (*.txt *.log *.html *.htm);;All Files (*.*)",
        )
        if not file_name:
            return
        input_path = Path(file_name)
        self._run_operation(
            name="test-parser",
            initial_message="Testing parser...",
            operation=lambda: self.parser_service.test_parser(parser.parser_id, input_path),
            on_success=lambda result: self._handle_test_result(parser.name, result),
            on_failure=lambda message, _details: QMessageBox.critical(self, "Test Parser", message),
            button_overrides={self.test_button: "Testing..."},
            stage_messages=["Preparing test...", "Starting Java parser...", "Processing sample log...", "Reading parser result...", "Finalizing test..."],
        )

    def _handle_test_result(self, parser_name: str, result: object) -> None:
        details = (
            f"Parser: {parser_name}\n"
            f"Matched: {result.matched}\n"
            f"Confidence: {result.confidence}\n"
            f"Status: {result.status}\n"
            f"Exit code: {result.exit_code}\n"
            f"APDU count: {result.apdu_count}\n"
            f"Warnings: {result.warning_count}\n"
            f"Errors: {result.error_count}\n"
            f"Elapsed: {result.elapsed_ms} ms\n\n"
            f"Reason: {result.reason}"
        )
        if result.output_path:
            details += f"\nOutput: {result.output_path}"
        if result.stdout:
            details += f"\n\nSTDOUT:\n{result.stdout}"
        if result.stderr:
            details += f"\n\nSTDERR:\n{result.stderr}"
        if result.errors:
            details += "\n\nErrors:\n" + "\n".join(result.errors)
        self.operation_status_label.setText(
            f"Parser test completed: {result.apdu_count} APDUs found." if result.success else "Parser test failed."
        )
        self.operation_frame.show()
        self._show_report("Test Parser", parser_name, details)


class TextReportDialog(QDialog):
    def __init__(self, title: str, heading: str, content: str, parent=None) -> None:
        super().__init__(parent)
        self.setWindowTitle(title)
        self.resize(900, 620)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 16, 16, 16)
        layout.setSpacing(12)

        heading_label = QLabel(heading)
        heading_label.setObjectName("panelTitle")
        layout.addWidget(heading_label)

        browser = QTextBrowser()
        browser.setPlainText(content)
        layout.addWidget(browser, 1)

        actions = QHBoxLayout()
        actions.addStretch(1)
        copy_button = QPushButton("Copy")
        close_button = QPushButton("Close")
        copy_button.clicked.connect(lambda: QGuiApplication.clipboard().setText(content))
        close_button.clicked.connect(self.accept)
        actions.addWidget(copy_button)
        actions.addWidget(close_button)
        layout.addLayout(actions)


class AddLegacyJavaExtractorDialog(QDialog):
    def __init__(self, parser_service: ParserManagementService, parent=None) -> None:
        super().__init__(parent)
        self.parser_service = parser_service
        self._install_request: dict | None = None
        self._completed_request: dict | None = None
        self._busy = False
        self._thread_pool = QThreadPool(self)
        self._status_timer = QTimer(self)
        self._status_timer.setInterval(900)
        self._status_timer.timeout.connect(self._advance_stage)
        self._stage_messages: list[str] = []
        self._stage_index = 0
        self.setWindowTitle("Add Legacy Java Extractor")
        self.resize(760, 520)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 16, 16, 16)
        layout.setSpacing(12)

        title = QLabel("Add Legacy Java Extractor")
        title.setObjectName("panelTitle")
        subtitle = QLabel(
            "Wrap an old standalone Java extractor with main(String[] args) into a managed parser plugin."
        )
        subtitle.setObjectName("secondaryText")
        subtitle.setWordWrap(True)
        layout.addWidget(title)
        layout.addWidget(subtitle)

        form = QFormLayout()
        form.setSpacing(10)

        self.source_input = QLineEdit()
        self.source_input.setReadOnly(True)
        browse_source = QPushButton("Browse...")
        browse_source.clicked.connect(self._browse_source)
        source_row = QHBoxLayout()
        source_row.addWidget(self.source_input, 1)
        source_row.addWidget(browse_source)
        form.addRow("Source file", _wrap_layout(source_row))

        self.inspect_label = QLabel("Choose a .java file to inspect its public class and main method.")
        self.inspect_label.setWordWrap(True)
        self.inspect_label.setObjectName("secondaryText")
        form.addRow("Inspection", self.inspect_label)

        self.parser_name_input = QLineEdit()
        self.parser_id_input = QLineEdit()
        self.version_input = QLineEdit("1.0.0")
        self.extensions_input = QLineEdit(".txt, .log")
        form.addRow("Parser name", self.parser_name_input)
        form.addRow("Parser ID", self.parser_id_input)
        form.addRow("Version", self.version_input)
        form.addRow("Extensions", self.extensions_input)

        self.command_pattern_combo = QComboBox()
        self.command_pattern_combo.addItem("<inputFile> <outputFile>", "INPUT_FILE_OUTPUT_FILE")
        self.command_pattern_combo.addItem("<inputFile>", "INPUT_FILE")
        self.command_pattern_combo.currentIndexChanged.connect(self._sync_output_hint)
        form.addRow("Command pattern", self.command_pattern_combo)

        self.output_file_name_input = QLineEdit("apdus.txt")
        form.addRow("Output filename", self.output_file_name_input)

        self.sample_input = QLineEdit()
        self.sample_input.setReadOnly(True)
        browse_sample = QPushButton("Browse...")
        browse_sample.clicked.connect(self._browse_sample)
        sample_row = QHBoxLayout()
        sample_row.addWidget(self.sample_input, 1)
        sample_row.addWidget(browse_sample)
        form.addRow("Sample log", _wrap_layout(sample_row))

        layout.addLayout(form)

        self.status_label = QLabel(
            "The extractor will be compiled, tested against the sample log, and installed only if the test succeeds."
        )
        self.status_label.setWordWrap(True)
        self.status_label.setObjectName("secondaryText")
        layout.addWidget(self.status_label)

        self.progress_frame = QFrame()
        self.progress_frame.setObjectName("inlineStatusFrame")
        progress_layout = QVBoxLayout(self.progress_frame)
        progress_layout.setContentsMargins(12, 10, 12, 10)
        progress_layout.setSpacing(8)
        self.progress_label = QLabel("")
        self.progress_label.setObjectName("secondaryText")
        self.progress_label.setWordWrap(True)
        self.progress_bar = QProgressBar()
        self.progress_bar.setRange(0, 0)
        self.progress_bar.setTextVisible(False)
        progress_layout.addWidget(self.progress_label)
        progress_layout.addWidget(self.progress_bar)
        self.progress_frame.hide()
        layout.addWidget(self.progress_frame)

        actions = QHBoxLayout()
        actions.addStretch(1)
        self.cancel_button = QPushButton("Cancel")
        self.install_button = QPushButton("Install")
        self.install_button.clicked.connect(self._submit)
        self.cancel_button.clicked.connect(self.reject)
        actions.addWidget(self.cancel_button)
        actions.addWidget(self.install_button)
        layout.addLayout(actions)

    def completed_request(self) -> dict | None:
        return self._completed_request

    def _browse_source(self) -> None:
        file_name, _ = QFileDialog.getOpenFileName(
            self,
            "Choose Legacy Java Extractor",
            str(Path.home()),
            "Java Source (*.java);;All Files (*.*)",
        )
        if not file_name:
            return
        path = Path(file_name)
        self.source_input.setText(str(path))
        try:
            inspection = self.parser_service.inspect_legacy_source(path)
            if inspection.success:
                self.inspect_label.setText(
                    f"Public class: {inspection.public_class_name} | Main class: {inspection.main_class_name or inspection.public_class_name}"
                )
                if not self.parser_name_input.text().strip():
                    self.parser_name_input.setText(_title_from_class_name(inspection.public_class_name))
                if not self.parser_id_input.text().strip():
                    self.parser_id_input.setText(_parser_id_from_class_name(inspection.public_class_name))
                self.status_label.setText(
                    "The extractor looks compatible. Choose metadata, command pattern, and a sample log to continue."
                )
            else:
                details = "\n".join(inspection.diagnostics) if inspection.diagnostics else inspection.message
                self.inspect_label.setText(inspection.message)
                self.status_label.setText(details or inspection.message)
        except Exception as exc:
            self.inspect_label.setText(str(exc))

    def _browse_sample(self) -> None:
        file_name, _ = QFileDialog.getOpenFileName(
            self,
            "Choose Sample Log",
            str(Path.home()),
            "Supported Logs (*.txt *.log *.html *.htm);;All Files (*.*)",
        )
        if file_name:
            self.sample_input.setText(file_name)

    def _sync_output_hint(self) -> None:
        pattern = self.command_pattern_combo.currentData()
        self.output_file_name_input.setEnabled(pattern == "INPUT_FILE")

    def _submit(self) -> None:
        if self._busy:
            return
        source_file = self.source_input.text().strip()
        parser_name = self.parser_name_input.text().strip()
        parser_id = self.parser_id_input.text().strip()
        version = self.version_input.text().strip() or "1.0.0"
        extensions = [item.strip() for item in self.extensions_input.text().split(",") if item.strip()]
        sample_log = self.sample_input.text().strip()
        if not source_file or not parser_name or not parser_id or not extensions or not sample_log:
            QMessageBox.warning(
                self,
                "Add Legacy Java Extractor",
                "Source file, parser name, parser ID, extensions, and sample log are required.",
            )
            return
        self._install_request = {
            "source_path": Path(source_file),
            "parser_name": parser_name,
            "parser_id": parser_id,
            "version": version,
            "supported_extensions": extensions,
            "command_pattern": str(self.command_pattern_combo.currentData()),
            "output_file_name": self.output_file_name_input.text().strip() or "apdus.txt",
            "sample_input": Path(sample_log),
        }
        self._begin_install()

    def _begin_install(self) -> None:
        if self._install_request is None:
            return
        self._busy = True
        self.setCursor(Qt.CursorShape.WaitCursor)
        self.install_button.setText("Installing...")
        self.install_button.setEnabled(False)
        self.cancel_button.setEnabled(False)
        self._set_form_enabled(False)
        self.progress_label.setText("Inspecting Java source...")
        self.progress_frame.show()
        self._stage_messages = [
            "Inspecting Java source...",
            "Compiling extractor...",
            "Creating wrapper plugin...",
            "Validating plugin...",
            "Testing with sample log...",
            "Installing parser...",
            "Refreshing parser list...",
        ]
        self._stage_index = 0
        self._status_timer.start()
        worker = CallableWorker(lambda: self.parser_service.install_legacy_source(**self._install_request))
        worker.signals.finished.connect(self._install_finished)
        worker.signals.failed.connect(self._install_failed)
        QTimer.singleShot(0, lambda current=worker: self._thread_pool.start(current))

    def _advance_stage(self) -> None:
        if not self._stage_messages:
            return
        index = min(self._stage_index, len(self._stage_messages) - 1)
        self.progress_label.setText(self._stage_messages[index])
        if self._stage_index < len(self._stage_messages) - 1:
            self._stage_index += 1

    def _install_finished(self, result: object) -> None:
        self._restore_after_install()
        if not result.success:
            details = "\n\n".join(
                part
                for part in [
                    result.message,
                    f"APDU count: {result.apdu_count}" if result.apdu_count else "",
                    f"Output: {result.generated_output_path}" if result.generated_output_path else "",
                    f"STDOUT:\n{result.stdout}" if result.stdout else "",
                    f"STDERR:\n{result.stderr}" if result.stderr else "",
                    "\n".join(result.warnings) if result.warnings else "",
                    result.compile_log,
                ]
                if part
            )
            self.progress_label.setText("Installation failed.")
            self.progress_frame.show()
            self.status_label.setText(result.message)
            self._show_report("Add Legacy Java Extractor", result.message, details or result.message)
            return
        self._completed_request = dict(self._install_request or {})
        self.progress_label.setText("Parser installed successfully.")
        self.progress_frame.show()
        self.accept()

    def _install_failed(self, message: str, details: str) -> None:
        self._restore_after_install()
        self.progress_label.setText("Installation failed.")
        self.progress_frame.show()
        self.status_label.setText(message)
        self._show_report("Add Legacy Java Extractor", message, details or message)

    def _restore_after_install(self) -> None:
        self._busy = False
        self.unsetCursor()
        self._status_timer.stop()
        self.install_button.setText("Install")
        self.install_button.setEnabled(True)
        self.cancel_button.setEnabled(True)
        self._set_form_enabled(True)

    def _set_form_enabled(self, enabled: bool) -> None:
        for widget in (
            self.source_input,
            self.parser_name_input,
            self.parser_id_input,
            self.version_input,
            self.extensions_input,
            self.command_pattern_combo,
            self.output_file_name_input,
            self.sample_input,
        ):
            widget.setEnabled(enabled)

        for button in self.findChildren(QPushButton):
            if button not in {self.install_button, self.cancel_button}:
                button.setEnabled(enabled)

    def _show_report(self, title: str, heading: str, content: str) -> None:
        dialog = TextReportDialog(title, heading, content, self)
        dialog.exec()


def _wrap_layout(inner_layout: QHBoxLayout) -> QWidget:
    widget = QWidget()
    widget.setLayout(inner_layout)
    return widget


def _title_from_class_name(class_name: str) -> str:
    if not class_name:
        return ""
    parts = re.findall(r"[A-Z]?[a-z0-9]+|[A-Z]+(?=[A-Z]|$)", class_name)
    return " ".join(parts) if parts else class_name


def _parser_id_from_class_name(class_name: str) -> str:
    if not class_name:
        return ""
    snake = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", class_name).lower()
    return re.sub(r"[^a-z0-9_]+", "_", snake).strip("_")
