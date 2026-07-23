from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QThreadPool, Qt, QUrl
from PySide6.QtGui import QAction, QCloseEvent, QDesktopServices
from PySide6.QtWidgets import (
    QFileDialog,
    QFrame,
    QHBoxLayout,
    QLabel,
    QMainWindow,
    QMenu,
    QMessageBox,
    QPushButton,
    QSplitter,
    QVBoxLayout,
    QWidget,
)

from apdu_parser.core.models import FilterMode, ImportedLogItem, LogStatus
from apdu_parser.core.workflow import WorkflowStore
from apdu_parser.services.config_service import ConfigService
from apdu_parser.services.java_parser_service import JobHandle, JavaParserService
from apdu_parser.services.logging_service import LoggingService
from apdu_parser.services.output_service import OutputService
from apdu_parser.services.parser_management_service import ParserManagementService
from apdu_parser.services.path_service import PathService
from apdu_parser.ui.dialogs.manage_parsers_dialog import ManageParsersDialog
from apdu_parser.ui.dialogs.settings_dialog import SettingsDialog
from apdu_parser.ui.theme import load_stylesheet
from apdu_parser.ui.widgets.drop_zone import DropZone
from apdu_parser.ui.widgets.filter_bar import FilterBar
from apdu_parser.ui.widgets.log_table import ImportedLogsTable, ImportedLogsTableModel
from apdu_parser.ui.widgets.result_tabs import ResultTabs
from apdu_parser.workers.parse_worker import ParseRequest, ParseWorker


class MainWindow(QMainWindow):
    def __init__(
        self,
        *,
        config_service: ConfigService,
        path_service: PathService,
        output_service: OutputService,
        logging_service: LoggingService,
        java_service: JavaParserService,
        parser_management_service: ParserManagementService,
    ) -> None:
        super().__init__()
        self.config_service = config_service
        self.path_service = path_service
        self.output_service = output_service
        self.logging_service = logging_service
        self.java_service = java_service
        self.parser_management_service = parser_management_service
        self.settings = self.config_service.load()
        self.store = WorkflowStore()
        self.jobs: dict[str, JobHandle] = {}

        self.thread_pool = QThreadPool(self)
        self.thread_pool.setMaxThreadCount(self.settings.max_parallel_jobs)

        self.setWindowTitle("APDU Parser")
        self.setStyleSheet(load_stylesheet())
        self.resize(self.settings.window_width, self.settings.window_height)
        if self.settings.window_x is not None and self.settings.window_y is not None:
            self.move(self.settings.window_x, self.settings.window_y)

        root = QWidget()
        root.setObjectName("centralwidget")
        outer = QVBoxLayout(root)
        outer.setContentsMargins(16, 16, 16, 16)
        outer.setSpacing(10)
        outer.addWidget(self._build_header())
        outer.addWidget(self._build_workspace(), 1)
        self.setCentralWidget(root)

        self._refresh_table()
        self._refresh_summary()
        self._update_selection_view()

    def _build_header(self) -> QWidget:
        card = QFrame()
        card.setObjectName("card")
        layout = QHBoxLayout(card)
        layout.setContentsMargins(20, 18, 20, 18)
        layout.setSpacing(16)

        text_col = QVBoxLayout()
        text_col.setSpacing(6)
        title = QLabel("APDU Parser")
        title.setObjectName("appTitle")
        subtitle = QLabel("Import logs, analyze with the Java parser, and inspect APDU results in one workspace.")
        subtitle.setObjectName("appSubtitle")
        text_col.addWidget(title)
        text_col.addWidget(subtitle)

        button_row = QHBoxLayout()
        button_row.setSpacing(10)
        self.import_button = QPushButton("Import Logs")
        self.analyze_button = QPushButton("Analyze")
        self.analyze_button.setObjectName("primaryButton")
        self.open_results_button = QPushButton("Open Results")
        self.more_button = QPushButton("More")
        self.import_button.clicked.connect(self._browse_files)
        self.analyze_button.clicked.connect(self._analyze_all)
        self.open_results_button.clicked.connect(self._open_results)
        self.more_button.clicked.connect(self._show_more_menu)

        for button in (self.import_button, self.analyze_button, self.open_results_button, self.more_button):
            button_row.addWidget(button)

        layout.addLayout(text_col, 1)
        layout.addLayout(button_row)
        return card

    def _build_workspace(self) -> QWidget:
        self.splitter = QSplitter(Qt.Orientation.Horizontal)
        self.splitter.setChildrenCollapsible(False)
        left_panel = self._build_left_panel()
        right_panel = self._build_right_panel()
        left_panel.setMinimumWidth(420)
        right_panel.setMinimumWidth(720)
        self.splitter.addWidget(left_panel)
        self.splitter.addWidget(right_panel)
        self.splitter.setStretchFactor(0, 0)
        self.splitter.setStretchFactor(1, 1)
        if self.settings.splitter_sizes:
            self.splitter.setSizes(self.settings.splitter_sizes)
        else:
            self.splitter.setSizes([700, 980])
        return self.splitter

    def _build_left_panel(self) -> QWidget:
        panel = QWidget()
        layout = QVBoxLayout(panel)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(10)

        self.drop_zone = DropZone()
        self.drop_zone.files_dropped.connect(self._import_files)
        self.drop_zone.browse_requested.connect(self._browse_files)
        layout.addWidget(self.drop_zone)

        logs_card = QFrame()
        logs_card.setObjectName("card")
        logs_layout = QVBoxLayout(logs_card)
        logs_layout.setContentsMargins(16, 16, 16, 16)
        logs_layout.setSpacing(8)
        header = QHBoxLayout()
        header.setSpacing(8)
        title = QLabel("Imported Logs")
        title.setObjectName("panelTitle")
        self.clear_button = QPushButton("Clear All")
        self.clear_button.setObjectName("linkButton")
        self.clear_button.clicked.connect(self._clear_logs)
        header.addWidget(title)
        header.addStretch(1)
        header.addWidget(self.clear_button)

        self.table_model = ImportedLogsTableModel()
        self.logs_table = ImportedLogsTable(self.table_model)
        self.logs_table.remove_requested.connect(self._remove_row)
        self.logs_table.selectionModel().selectionChanged.connect(lambda *_: self._update_selection_view())

        logs_layout.addLayout(header)
        logs_layout.addWidget(self.logs_table, 1)
        layout.addWidget(logs_card, 1)
        return panel

    def _build_right_panel(self) -> QWidget:
        panel = QWidget()
        layout = QVBoxLayout(panel)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(8)

        self.summary_card = QFrame()
        self.summary_card.setObjectName("summaryCard")
        summary_layout = QVBoxLayout(self.summary_card)
        summary_layout.setContentsMargins(16, 12, 16, 12)
        summary_layout.setSpacing(4)
        self.counts_label = QLabel("0 imported · 0 completed · 0 unsupported")
        self.counts_label.setObjectName("secondaryText")
        self.selection_title = QLabel("No log selected")
        self.selection_title.setObjectName("selectionTitle")
        self.selection_meta = QLabel("Import a log or choose one from the list to inspect APDUs and analysis output.")
        self.selection_meta.setObjectName("secondaryText")
        summary_layout.addWidget(self.counts_label)
        summary_layout.addWidget(self.selection_title)
        summary_layout.addWidget(self.selection_meta)
        layout.addWidget(self.summary_card)

        self.filter_bar = FilterBar()
        self.filter_bar.filter_changed.connect(self._change_filter)
        layout.addWidget(self.filter_bar)

        self.result_tabs = ResultTabs()
        layout.addWidget(self.result_tabs, 1)
        return panel

    def _browse_files(self) -> None:
        start_dir = self.settings.last_import_directory or str(Path.home())
        files, _ = QFileDialog.getOpenFileNames(
            self,
            "Import Logs",
            start_dir,
            "Supported Logs (*.txt *.log *.html *.htm);;All Files (*.*)",
        )
        if files:
            self.settings.last_import_directory = str(Path(files[0]).parent)
            self._import_files([Path(path) for path in files])

    def _import_files(self, paths: list[Path]) -> None:
        valid = [path for path in paths if path.is_file()]
        if not valid:
            QMessageBox.warning(self, "Import Logs", "No valid local files were selected.")
            return
        self.store.add_files(valid)
        self._refresh_table()
        self._refresh_summary()
        if self.table_model.rowCount() > 0 and self.logs_table.currentIndex().row() < 0:
            self.logs_table.selectRow(0)
            self.logs_table.setCurrentIndex(self.table_model.index(0, 0))

    def _refresh_table(self) -> None:
        self.table_model.set_items(self.store.items)

    def _refresh_summary(self) -> None:
        items = self.store.items
        completed = sum(1 for item in items if item.status == LogStatus.COMPLETED)
        unsupported = sum(1 for item in items if item.status == LogStatus.UNSUPPORTED)
        self.counts_label.setText(f"{len(items)} imported · {completed} completed · {unsupported} unsupported")

    def _selected_item(self) -> ImportedLogItem | None:
        index = self.logs_table.currentIndex()
        if not index.isValid():
            return None
        return self.table_model.item_at(index.row())

    def _selection_parser_text(self, item: ImportedLogItem) -> str:
        if item.status == LogStatus.UNSUPPORTED:
            return "No parser detected"
        if not item.detected_format or item.detected_format == "Pending":
            return "Parser pending"
        return item.detected_format

    def _update_selection_view(self) -> None:
        item = self._selected_item()
        if item is None:
            self.selection_title.setText("No log selected")
            self.selection_meta.setText("Import a log or choose one from the list to inspect APDUs and analysis output.")
            self.result_tabs.show_empty_state()
            self._refresh_summary()
            return

        self.selection_title.setText(item.file_name)
        self.selection_meta.setText(f"{item.status_text} · {self._selection_parser_text(item)}")

        if item.status == LogStatus.COMPLETED and item.result is not None:
            self.result_tabs.set_result(item.file_name, item.result)
        elif item.status == LogStatus.FAILED:
            self.result_tabs.show_status_state(
                "Parser failure",
                "The Java parser did not complete successfully for this log.",
                error_details=item.error_message or (item.result.output_files.stderr_log if item.result else ""),
                error_summary=item.result_summary or (item.result.message if item.result else "Parser failure"),
                preferred_tab="errors",
            )
        elif item.status == LogStatus.UNSUPPORTED:
            self.result_tabs.show_status_state(
                "Unsupported log format",
                "No internal parser matched this file.",
                error_details=item.error_message or (item.result.message if item.result else ""),
                error_summary=item.result_summary or "Unsupported format",
                preferred_tab="analysis",
            )
        elif item.status == LogStatus.CANCELLED:
            self.result_tabs.show_status_state(
                "Analysis cancelled",
                "The analysis was cancelled before completion.",
                preferred_tab="analysis",
            )
        elif item.status == LogStatus.ANALYZING:
            self.result_tabs.show_status_state(
                "Analysis in progress",
                "The selected log is being analyzed in the background.",
                preferred_tab="analysis",
            )
        elif item.result is not None:
            self.result_tabs.set_result(item.file_name, item.result)
        else:
            self.result_tabs.show_status_state(
                "Not analyzed yet",
                "Select Analyze to populate APDUs, analysis events, applets, and errors.",
                preferred_tab="analysis",
            )
        self._refresh_summary()

    def _change_filter(self, value: str) -> None:
        self.result_tabs.set_filter_mode(FilterMode(value))

    def _remove_row(self, row: int) -> None:
        item = self.table_model.item_at(row)
        if not item:
            return
        handle = self.jobs.get(item.item_id)
        if handle:
            handle.cancel()
        self.store.remove_item(item.item_id)
        self._refresh_table()
        self._refresh_summary()
        self._update_selection_view()

    def _clear_logs(self) -> None:
        for handle in self.jobs.values():
            handle.cancel()
        self.jobs.clear()
        self.store.clear()
        self._refresh_table()
        self._refresh_summary()
        self._update_selection_view()

    def _analyze_all(self) -> None:
        if not self.store.items:
            QMessageBox.information(self, "Analyze", "Import one or more logs first.")
            return
        for item in self.store.items:
            if item.active_job_id:
                continue
            json_path, artifacts_dir = self.output_service.allocate_result_paths(item)
            item.output_json_path = json_path
            item.artifacts_dir = artifacts_dir
            item.status = LogStatus.ANALYZING
            item.result_summary = "Queued"
            handle = JobHandle()
            self.jobs[item.item_id] = handle
            item.active_job_id = item.item_id
            worker = ParseWorker(
                self.java_service,
                ParseRequest(
                    item_id=item.item_id,
                    input_path=item.source_path,
                    json_output_path=json_path,
                    artifacts_dir=artifacts_dir,
                    timeout_seconds=self.settings.parser_timeout_seconds,
                ),
                handle,
            )
            worker.signals.started.connect(self._on_job_started)
            worker.signals.finished.connect(self._on_job_finished)
            worker.signals.failed.connect(self._on_job_failed)
            worker.signals.cancelled.connect(self._on_job_cancelled)
            self.thread_pool.start(worker)
        self._refresh_table()
        self._refresh_summary()

    def _on_job_started(self, item_id: str) -> None:
        item = self.store.get(item_id)
        if not item:
            return
        item.status = LogStatus.ANALYZING
        item.result_summary = "Analyzing"
        self._refresh_table()
        self._refresh_summary()
        self._update_selection_view()

    def _on_job_finished(self, item_id: str, result) -> None:
        item = self.store.update_result(item_id, result)
        if not item:
            return
        item.active_job_id = None
        self.jobs.pop(item_id, None)
        self._refresh_table()
        self._update_selection_view()

    def _on_job_failed(self, item_id: str, message: str, exit_code: int, stderr: str) -> None:
        item = self.store.get(item_id)
        if not item:
            return
        item.status = LogStatus.FAILED
        item.error_message = stderr or message
        item.result_summary = f"Failed ({exit_code})" if exit_code >= 0 else "Failed"
        item.active_job_id = None
        self.jobs.pop(item_id, None)
        self._refresh_table()
        self._update_selection_view()

    def _on_job_cancelled(self, item_id: str) -> None:
        item = self.store.get(item_id)
        if not item:
            return
        item.status = LogStatus.CANCELLED
        item.result_summary = "Cancelled"
        item.active_job_id = None
        self.jobs.pop(item_id, None)
        self._refresh_table()
        self._update_selection_view()

    def _open_results(self) -> None:
        target = self.path_service.output_root
        QDesktopServices.openUrl(QUrl.fromLocalFile(str(target)))

    def _show_more_menu(self) -> None:
        menu = QMenu(self)
        settings_action = QAction("Settings", self)
        settings_action.triggered.connect(self._open_settings)
        cancel_action = QAction("Cancel Running Jobs", self)
        cancel_action.triggered.connect(self._cancel_running_jobs)
        manage_parsers_action = QAction("Manage Parsers", self)
        manage_parsers_action.triggered.connect(self._open_manage_parsers)
        menu.addAction(settings_action)
        menu.addAction(manage_parsers_action)
        menu.addAction(cancel_action)
        menu.exec(self.more_button.mapToGlobal(self.more_button.rect().bottomLeft()))

    def _open_settings(self) -> None:
        dialog = SettingsDialog(self.settings, self)
        if dialog.exec():
            self.settings = dialog.apply_to(self.settings)
            self.thread_pool.setMaxThreadCount(self.settings.max_parallel_jobs)
            self.config_service.save(self.settings)

    def _cancel_running_jobs(self) -> None:
        for handle in self.jobs.values():
            handle.cancel()

    def _open_manage_parsers(self) -> None:
        dialog = ManageParsersDialog(self.parser_management_service, self)
        dialog.exec()

    def closeEvent(self, event: QCloseEvent) -> None:
        for handle in self.jobs.values():
            handle.cancel()
        self.settings.window_width = self.width()
        self.settings.window_height = self.height()
        self.settings.window_x = self.x()
        self.settings.window_y = self.y()
        self.settings.splitter_sizes = self.splitter.sizes()
        self.config_service.save(self.settings)
        super().closeEvent(event)
