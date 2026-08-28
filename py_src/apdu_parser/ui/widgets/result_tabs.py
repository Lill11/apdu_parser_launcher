from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QAbstractTableModel, QModelIndex, QSortFilterProxyModel, Qt
from PySide6.QtGui import QColor, QFont, QGuiApplication
from PySide6.QtWidgets import (
    QFileDialog,
    QDialog,
    QFrame,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QLineEdit,
    QPushButton,
    QPlainTextEdit,
    QStackedLayout,
    QTabWidget,
    QTableView,
    QTextBrowser,
    QTreeWidget,
    QTreeWidgetItem,
    QVBoxLayout,
    QWidget,
)

from apdu_parser.core.models import ApduRow, AppletPayload, FilterMode, ParseResult


class ApduTableModel(QAbstractTableModel):
    headers = ["Event", "APDU #", "Command", "Response", "Category", "Description"]

    def __init__(self) -> None:
        super().__init__()
        self.rows: list[ApduRow] = []

    def rowCount(self, parent: QModelIndex = QModelIndex()) -> int:
        return 0 if parent.isValid() else len(self.rows)

    def columnCount(self, parent: QModelIndex = QModelIndex()) -> int:
        return 0 if parent.isValid() else len(self.headers)

    def headerData(self, section: int, orientation, role: int = Qt.ItemDataRole.DisplayRole):
        if orientation == Qt.Orientation.Horizontal and role == Qt.ItemDataRole.DisplayRole:
            return self.headers[section]
        return None

    def data(self, index: QModelIndex, role: int = Qt.ItemDataRole.DisplayRole):
        if not index.isValid():
            return None
        row = self.rows[index.row()]
        if role == Qt.ItemDataRole.DisplayRole:
            return {
                0: row.event_sequence,
                1: "" if row.index is None else row.index,
                2: row.command,
                3: row.response,
                4: row.category,
                5: row.description,
            }.get(index.column())
        if role == Qt.ItemDataRole.UserRole:
            return row
        if row.event_type == "RESET":
            if role == Qt.ItemDataRole.BackgroundRole:
                return QColor("#E8F1FF")
            if role == Qt.ItemDataRole.ForegroundRole:
                return QColor("#123A70")
            if role == Qt.ItemDataRole.FontRole:
                font = QFont()
                font.setBold(True)
                return font
            if role == Qt.ItemDataRole.ToolTipRole:
                return f"Cold Reset at source line {row.source_line}\nATR: {row.atr}"
        return None

    def set_rows(self, rows: list[ApduRow]) -> None:
        self.beginResetModel()
        self.rows = list(rows)
        self.endResetModel()


class ApduFilterProxyModel(QSortFilterProxyModel):
    def __init__(self) -> None:
        super().__init__()
        self.filter_mode = FilterMode.ALL
        self.search_text = ""

    def set_filter_mode(self, mode: FilterMode) -> None:
        self.filter_mode = mode
        self.invalidateFilter()

    def set_search_text(self, text: str) -> None:
        self.search_text = text.lower().strip()
        self.invalidateFilter()

    def filterAcceptsRow(self, source_row: int, source_parent: QModelIndex) -> bool:
        row: ApduRow = self.sourceModel().index(source_row, 0, source_parent).data(Qt.ItemDataRole.UserRole)
        if self.filter_mode != FilterMode.ALL and self.filter_mode.value not in row.filters:
            return False
        if self.search_text:
            haystack = " ".join(
                [row.command, row.response, row.category, row.description, row.note, row.reset_type, row.atr]
            ).lower()
            return self.search_text in haystack
        return True


class ResultTabs(QWidget):
    def __init__(self) -> None:
        super().__init__()
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(6)

        self.tabs = QTabWidget()
        self.tabs.setObjectName("resultTabs")
        layout.addWidget(self.tabs)

        self.apdu_model = ApduTableModel()
        self.apdu_proxy = ApduFilterProxyModel()
        self.apdu_proxy.setSourceModel(self.apdu_model)

        self.apdu_view = QTableView()
        self.apdu_view.setModel(self.apdu_proxy)
        self.apdu_view.verticalHeader().hide()
        self.apdu_view.verticalHeader().setDefaultSectionSize(36)
        self.apdu_view.horizontalHeader().setSectionResizeMode(5, QHeaderView.ResizeMode.Stretch)
        self.apdu_view.horizontalHeader().setSectionResizeMode(2, QHeaderView.ResizeMode.Stretch)
        self.apdu_view.horizontalHeader().setSectionResizeMode(3, QHeaderView.ResizeMode.Stretch)
        self.apdu_view.setSelectionBehavior(QTableView.SelectionBehavior.SelectRows)
        self.apdu_view.setSelectionMode(QTableView.SelectionMode.SingleSelection)
        self.apdu_view.setShowGrid(False)

        apdu_tab = QWidget()
        apdu_layout = QVBoxLayout(apdu_tab)
        apdu_layout.setContentsMargins(0, 0, 0, 0)
        apdu_layout.setSpacing(8)
        toolbar = QHBoxLayout()
        toolbar.setSpacing(8)
        self.search_input = QLineEdit()
        self.search_input.setPlaceholderText("Search APDUs, status words, tags...")
        self.search_input.textChanged.connect(self.apdu_proxy.set_search_text)
        self.copy_selected_btn = QPushButton("Copy Selected")
        self.copy_all_btn = QPushButton("Copy All")
        self.export_btn = QPushButton("Export")
        self.generate_java_btn = QPushButton("Generate Java")
        self.generate_java_btn.setEnabled(False)
        self.copy_selected_btn.clicked.connect(self.copy_selected)
        self.copy_all_btn.clicked.connect(self.copy_all)
        self.export_btn.clicked.connect(self.export_apdus)
        self.generate_java_btn.clicked.connect(self.show_generated_java)
        toolbar.addWidget(self.search_input, 1)
        toolbar.addWidget(self.copy_selected_btn)
        toolbar.addWidget(self.copy_all_btn)
        toolbar.addWidget(self.export_btn)
        toolbar.addWidget(self.generate_java_btn)
        self.apdu_content = QWidget()
        apdu_content_layout = QVBoxLayout(self.apdu_content)
        apdu_content_layout.setContentsMargins(0, 0, 0, 0)
        apdu_content_layout.setSpacing(8)
        apdu_content_layout.addLayout(toolbar)
        apdu_content_layout.addWidget(self.apdu_view)
        self.apdu_state = self._build_state_panel()
        self.apdu_stack = QStackedLayout()
        self.apdu_stack.setContentsMargins(0, 0, 0, 0)
        self.apdu_stack.addWidget(self.apdu_content)
        self.apdu_stack.addWidget(self.apdu_state)
        apdu_layout.addLayout(self.apdu_stack)

        self.analysis_browser = QTextBrowser()
        self.analysis_browser.setOpenExternalLinks(False)
        self.analysis_state = self._build_state_panel()
        analysis_tab = QWidget()
        analysis_layout = QVBoxLayout(analysis_tab)
        analysis_layout.setContentsMargins(0, 0, 0, 0)
        self.analysis_stack = QStackedLayout()
        self.analysis_stack.setContentsMargins(0, 0, 0, 0)
        self.analysis_stack.addWidget(self.analysis_browser)
        self.analysis_stack.addWidget(self.analysis_state)
        analysis_layout.addLayout(self.analysis_stack)

        self.applets_tree = QTreeWidget()
        self.applets_tree.setHeaderLabels(["Applet File", "Details"])
        self.applets_state = self._build_state_panel()
        applets_tab = QWidget()
        applets_layout = QVBoxLayout(applets_tab)
        applets_layout.setContentsMargins(0, 0, 0, 0)
        self.applets_stack = QStackedLayout()
        self.applets_stack.setContentsMargins(0, 0, 0, 0)
        self.applets_stack.addWidget(self.applets_tree)
        self.applets_stack.addWidget(self.applets_state)
        applets_layout.addLayout(self.applets_stack)

        errors_tab = QWidget()
        errors_layout = QVBoxLayout(errors_tab)
        errors_layout.setContentsMargins(0, 0, 0, 0)
        errors_layout.setSpacing(8)
        self.error_summary = QLabel("No parser errors.")
        self.error_summary.setObjectName("errorSummary")
        self.error_copy = QPushButton("Copy Details")
        self.error_copy.clicked.connect(self.copy_error_details)
        self.error_details = QPlainTextEdit()
        self.error_details.setReadOnly(True)
        self.error_details.setVisible(False)
        self.error_toggle = QPushButton("Show Technical Details")
        self.error_toggle.setCheckable(True)
        self.error_toggle.toggled.connect(self._toggle_error_details)
        header = QHBoxLayout()
        header.setSpacing(8)
        header.addWidget(self.error_summary, 1)
        header.addWidget(self.error_copy)
        self.errors_content = QWidget()
        errors_content_layout = QVBoxLayout(self.errors_content)
        errors_content_layout.setContentsMargins(0, 0, 0, 0)
        errors_content_layout.setSpacing(8)
        errors_content_layout.addLayout(header)
        errors_content_layout.addWidget(self.error_toggle)
        errors_content_layout.addWidget(self.error_details, 1)
        self.errors_state = self._build_state_panel()
        self.errors_stack = QStackedLayout()
        self.errors_stack.setContentsMargins(0, 0, 0, 0)
        self.errors_stack.addWidget(self.errors_content)
        self.errors_stack.addWidget(self.errors_state)
        errors_layout.addLayout(self.errors_stack)

        self.tabs.addTab(apdu_tab, "APDUs")
        self.tabs.addTab(analysis_tab, "Analysis")
        self.tabs.addTab(applets_tab, "Applets")
        self.tabs.addTab(errors_tab, "Errors")

        self.current_result: ParseResult | None = None
        self.current_item_name: str = ""

    def _build_state_panel(self) -> QWidget:
        container = QWidget()
        layout = QVBoxLayout(container)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.addStretch(1)
        card = QFrame()
        card.setObjectName("stateCard")
        card_layout = QVBoxLayout(card)
        card_layout.setContentsMargins(28, 24, 28, 24)
        card_layout.setSpacing(8)
        icon = QLabel("i")
        icon.setObjectName("stateIcon")
        icon.setAlignment(Qt.AlignmentFlag.AlignCenter)
        title = QLabel("")
        title.setObjectName("stateTitle")
        title.setAlignment(Qt.AlignmentFlag.AlignCenter)
        message = QLabel("")
        message.setObjectName("stateMessage")
        message.setWordWrap(True)
        message.setAlignment(Qt.AlignmentFlag.AlignCenter)
        action = QLabel("")
        action.setObjectName("stateHint")
        action.setWordWrap(True)
        action.setAlignment(Qt.AlignmentFlag.AlignCenter)
        card_layout.addWidget(icon, 0, Qt.AlignmentFlag.AlignCenter)
        card_layout.addWidget(title)
        card_layout.addWidget(message)
        card_layout.addWidget(action)
        layout.addWidget(card, 0, Qt.AlignmentFlag.AlignCenter)
        layout.addStretch(1)
        container._state_icon = icon
        container._state_title = title
        container._state_message = message
        container._state_action = action
        return container

    @staticmethod
    def _set_state_panel(widget: QWidget, *, icon: str, title: str, message: str, action: str) -> None:
        widget._state_icon.setText(icon)
        widget._state_title.setText(title)
        widget._state_message.setText(message)
        widget._state_action.setText(action)

    def set_filter_mode(self, mode: FilterMode) -> None:
        self.apdu_proxy.set_filter_mode(mode)
        self._render_analysis()

    def set_result(self, item_name: str, result: ParseResult | None) -> None:
        self.current_result = result
        self.current_item_name = item_name
        self.generate_java_btn.setEnabled(bool(result and result.generated_java))
        if result is None:
            self.show_empty_state()
            return
        self.apdu_stack.setCurrentIndex(0)
        self.analysis_stack.setCurrentIndex(0)
        self.applets_stack.setCurrentIndex(0)
        self.errors_stack.setCurrentIndex(0)
        self.apdu_model.set_rows(result.events)
        self._render_analysis()
        self._render_applets(result.applets)
        self._render_errors(result)

    def show_empty_state(self) -> None:
        self.current_result = None
        self.generate_java_btn.setEnabled(False)
        self.apdu_model.set_rows([])
        self._set_state_panel(
            self.apdu_state,
            icon="·",
            title="No APDU result yet",
            message="Import a log and run Analyze to populate the APDU table.",
            action="Use Import Logs to add one or more files.",
        )
        self._set_state_panel(
            self.analysis_state,
            icon="·",
            title="No log selected yet",
            message="Import logs and choose one from the list to review extracted APDUs and analysis events.",
            action="The Analysis tab will summarize the selected result.",
        )
        self._set_state_panel(
            self.applets_state,
            icon="·",
            title="No applet data yet",
            message="Applet extraction appears here when the selected log contains applicable data.",
            action="Run Analyze on a supported log to populate this tab.",
        )
        self._set_state_panel(
            self.errors_state,
            icon="·",
            title="No parser errors",
            message="Errors and technical details will appear here when a parse fails.",
            action="Run Analyze to collect stderr and diagnostics when needed.",
        )
        self.apdu_stack.setCurrentIndex(1)
        self.analysis_stack.setCurrentIndex(1)
        self.applets_stack.setCurrentIndex(1)
        self.errors_stack.setCurrentIndex(1)
        self.error_summary.setText("No parser errors.")
        self.error_details.setPlainText("")
        self.error_toggle.setChecked(False)
        self.tabs.setCurrentIndex(1)

    def show_status_state(
        self,
        title: str,
        message: str,
        *,
        error_details: str = "",
        error_summary: str = "No parser errors.",
        preferred_tab: str = "analysis",
    ) -> None:
        self.current_result = None
        self.generate_java_btn.setEnabled(False)
        self.apdu_model.set_rows([])
        self._set_state_panel(
            self.apdu_state,
            icon="·",
            title="No APDU rows available",
            message=message,
            action="APDU rows will appear here after a successful parse.",
        )
        self._set_state_panel(
            self.analysis_state,
            icon="!",
            title=title,
            message=message,
            action="Verify the log source or review technical details when available.",
        )
        self._set_state_panel(
            self.applets_state,
            icon="·",
            title="No applet extraction available",
            message="Applet extraction was not produced for this state.",
            action="Run Analyze on a supported log to populate this tab.",
        )
        self.apdu_stack.setCurrentIndex(1)
        self.analysis_stack.setCurrentIndex(1)
        self.applets_stack.setCurrentIndex(1)
        self.error_summary.setText(error_summary)
        self.error_details.setPlainText(error_details)
        self.error_toggle.setChecked(bool(error_details and preferred_tab == "errors"))
        if error_details or preferred_tab == "errors":
            self.errors_stack.setCurrentIndex(0)
        else:
            self._set_state_panel(
                self.errors_state,
                icon="·",
                title="No technical error details",
                message="No parser stderr or diagnostics were recorded for this state.",
                action="Technical details appear here when the parser reports them.",
            )
            self.errors_stack.setCurrentIndex(1)
        self.tabs.setCurrentIndex(3 if preferred_tab == "errors" else 1)

    def _render_analysis(self) -> None:
        if self.current_result is None:
            return
        parts = []
        filter_mode = self.apdu_proxy.filter_mode
        for event in self.current_result.analysis:
            include = filter_mode == FilterMode.ALL
            if not include and filter_mode != FilterMode.ALL:
                matching = next((row for row in self.current_result.apdus if row.index == event.index), None)
                if matching and filter_mode.value in matching.filters:
                    include = True
            if not include:
                continue
            parts.append(
                f"<div class='analysisEvent'><div class='analysisTitle'>"
                f"[{event.event_sequence or event.index:04d}] {event.title}</div>"
                f"<div class='analysisMeta'>Severity: {event.severity} | SW: {event.status_word} | Source line: {event.source_line}</div>"
                f"<div class='analysisMessage'>{event.message or 'No additional note.'}</div></div>"
            )
        if not parts:
            self._set_state_panel(
                self.analysis_state,
                icon="·",
                title="No analysis events for this filter",
                message="The selected filter did not match any APDU analysis events.",
                action="Try All or switch to another filter chip.",
            )
            self.analysis_stack.setCurrentIndex(1)
            return
        self.analysis_browser.setHtml("".join(parts))
        self.analysis_stack.setCurrentIndex(0)

    def _render_applets(self, payload: AppletPayload) -> None:
        self.applets_tree.clear()
        if payload.status == "not_applicable":
            self._set_state_panel(
                self.applets_state,
                icon="·",
                title="Applet extraction not applicable",
                message=payload.message or "This log does not contain a compatible applet extraction flow.",
                action="Select another log if you expect GlobalPlatform applet output.",
            )
            self.applets_stack.setCurrentIndex(1)
            return
        if payload.status == "no_applets":
            self._set_state_panel(
                self.applets_state,
                icon="·",
                title="No applets found",
                message=payload.message or "No GlobalPlatform applet flow was found in the selected log.",
                action="A supported applet flow will appear here as grouped output files.",
            )
            self.applets_stack.setCurrentIndex(1)
            return
        if not payload.files:
            self._set_state_panel(
                self.applets_state,
                icon="·",
                title="No applet files generated",
                message="Applet extraction did not produce structured output files.",
                action="Review the Analysis or Errors tab for more context.",
            )
            self.applets_stack.setCurrentIndex(1)
            return
        all_clean = QTreeWidgetItem(["all_clean.lop", f"{len(payload.all_clean)} lines"])
        for line in payload.all_clean:
            all_clean.addChild(QTreeWidgetItem(["", line]))
        self.applets_tree.addTopLevelItem(all_clean)
        for applet in payload.files:
            node = QTreeWidgetItem([applet.name, f"{len(applet.lines)} lines"])
            for line in applet.lines:
                node.addChild(QTreeWidgetItem(["", line]))
            self.applets_tree.addTopLevelItem(node)
        self.applets_tree.expandAll()
        self.applets_stack.setCurrentIndex(0)

    def _render_errors(self, result: ParseResult) -> None:
        if result.errors:
            error = result.errors[0]
            self.error_summary.setText(f"{error.message} (exit code {result.summary.exit_code})")
            detail = [f"Code: {error.code}", f"Exit code: {result.summary.exit_code}", "", error.details]
            if result.output_files.stderr_log:
                detail.extend(["", "stderr:", result.output_files.stderr_log])
            self.error_details.setPlainText("\n".join(detail))
            self.error_toggle.setChecked(True)
            self.errors_stack.setCurrentIndex(0)
        elif result.success:
            self.error_summary.setText("No parser errors.")
            self.error_details.setPlainText(result.output_files.stderr_log)
            self.error_toggle.setChecked(False)
            self._set_state_panel(
                self.errors_state,
                icon="·",
                title="No parser errors",
                message="The selected log completed successfully without parser failures.",
                action="If parser internals are needed later, stderr and diagnostics will appear here.",
            )
            self.errors_stack.setCurrentIndex(1)
        else:
            self.error_summary.setText(f"{result.message} (exit code {result.summary.exit_code})")
            self.error_details.setPlainText(result.output_files.stderr_log)
            self.error_toggle.setChecked(bool(result.output_files.stderr_log))
            self.errors_stack.setCurrentIndex(0)

    def _toggle_error_details(self, checked: bool) -> None:
        self.error_details.setVisible(checked)
        self.error_toggle.setText("Hide Technical Details" if checked else "Show Technical Details")

    def copy_selected(self) -> None:
        index = self.apdu_view.currentIndex()
        if not index.isValid():
            return
        row: ApduRow = self.apdu_proxy.index(index.row(), 0).data(Qt.ItemDataRole.UserRole)
        QGuiApplication.clipboard().setText(f"{row.command}\t{row.response}\t{row.description}")

    def copy_all(self) -> None:
        rows = []
        for i in range(self.apdu_proxy.rowCount()):
            row: ApduRow = self.apdu_proxy.index(i, 0).data(Qt.ItemDataRole.UserRole)
            apdu_number = "" if row.index is None else f" APDU {row.index:04d}"
            rows.append(
                f"[{row.event_sequence:04d}]{apdu_number} {row.command} -> {row.response} | {row.description}"
            )
        QGuiApplication.clipboard().setText("\n".join(rows))

    def export_apdus(self) -> None:
        if not self.current_result or not self.current_result.output_files.apdu_text:
            return
        target, _ = QFileDialog.getSaveFileName(self, "Export APDUs", f"{self.current_item_name}.txt", "Text Files (*.txt)")
        if target:
            Path(target).write_text(self.current_result.output_files.apdu_text.read_text(encoding="utf-8"), encoding="utf-8")

    def show_generated_java(self) -> None:
        if not self.current_result or not self.current_result.generated_java:
            return
        class_name = self.current_result.generated_java_class_name or Path(self.current_item_name).stem or "GeneratedApduTest"
        JavaSnippetDialog(
            self.current_result.generated_java,
            f"{class_name}.java",
            self,
        ).exec()

    def copy_error_details(self) -> None:
        QGuiApplication.clipboard().setText(self.error_details.toPlainText())


class JavaSnippetDialog(QDialog):
    def __init__(self, content: str, suggested_file_name: str, parent=None) -> None:
        super().__init__(parent)
        self.content = content
        self.suggested_file_name = suggested_file_name
        self.setWindowTitle("Generated Java Test")
        self.resize(920, 640)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(18, 18, 18, 18)
        layout.setSpacing(12)

        title = QLabel("Generated Java Test")
        title.setObjectName("panelTitle")
        helper = QLabel("Complete Java test class generated from Terminal-to-Card APDUs and Expected SW values.")
        helper.setWordWrap(True)
        editor = QPlainTextEdit()
        editor.setReadOnly(True)
        editor.setPlainText(content)

        actions = QHBoxLayout()
        actions.addStretch(1)
        copy_button = QPushButton("Copy")
        export_button = QPushButton("Export Java")
        close_button = QPushButton("Close")
        copy_button.clicked.connect(lambda: QGuiApplication.clipboard().setText(content))
        export_button.clicked.connect(self.export_java)
        close_button.clicked.connect(self.accept)
        actions.addWidget(copy_button)
        actions.addWidget(export_button)
        actions.addWidget(close_button)

        layout.addWidget(title)
        layout.addWidget(helper)
        layout.addWidget(editor, 1)
        layout.addLayout(actions)

    def export_java(self) -> None:
        target, _ = QFileDialog.getSaveFileName(
            self,
            "Export Java Test",
            self.suggested_file_name or "GeneratedApduTest.java",
            "Java Files (*.java);;Text Files (*.txt)",
        )
        if target:
            Path(target).write_text(self.content, encoding="utf-8")
