from __future__ import annotations

from PySide6.QtCore import QAbstractTableModel, QModelIndex, QRect, Qt, Signal
from PySide6.QtGui import QColor, QPainter, QPen
from PySide6.QtWidgets import QStyle, QStyledItemDelegate, QTableView

from apdu_parser.core.models import ImportedLogItem


class ImportedLogsTableModel(QAbstractTableModel):
    headers = ["Filename", "Format", "Status", "Result", "Remove"]

    def __init__(self) -> None:
        super().__init__()
        self.items: list[ImportedLogItem] = []

    def rowCount(self, parent: QModelIndex = QModelIndex()) -> int:
        return 0 if parent.isValid() else len(self.items)

    def columnCount(self, parent: QModelIndex = QModelIndex()) -> int:
        return 0 if parent.isValid() else len(self.headers)

    def data(self, index: QModelIndex, role: int = Qt.ItemDataRole.DisplayRole):
        if not index.isValid():
            return None
        item = self.items[index.row()]
        if role in (Qt.ItemDataRole.DisplayRole, Qt.ItemDataRole.EditRole):
            return {
                0: item.file_name,
                1: item.detected_format,
                2: item.status_text,
                3: item.result_summary,
                4: "Remove",
            }.get(index.column(), "")
        if role == Qt.ItemDataRole.ToolTipRole:
            if index.column() == 0:
                return str(item.source_path)
            if index.column() == 1:
                return item.detected_format
            if index.column() == 2:
                return item.status_text
            if index.column() == 3:
                return item.result_summary
        if role == Qt.ItemDataRole.DecorationRole and index.column() == 2:
            return self.status_color(item)
        if role == Qt.ItemDataRole.ForegroundRole and index.column() == 2:
            return self.status_color(item)
        if role == Qt.ItemDataRole.UserRole:
            return item.item_id
        return None

    def headerData(self, section: int, orientation, role: int = Qt.ItemDataRole.DisplayRole):
        if orientation == Qt.Orientation.Horizontal and role == Qt.ItemDataRole.DisplayRole:
            return self.headers[section]
        return None

    def set_items(self, items: list[ImportedLogItem]) -> None:
        self.beginResetModel()
        self.items = list(items)
        self.endResetModel()

    def item_at(self, row: int) -> ImportedLogItem | None:
        if 0 <= row < len(self.items):
            return self.items[row]
        return None

    @staticmethod
    def status_color(item: ImportedLogItem) -> QColor:
        return {
            "Pending": QColor("#94A3B8"),
            "Detecting": QColor("#D97706"),
            "Analyzing": QColor("#2563EB"),
            "Completed": QColor("#16A34A"),
            "Unsupported": QColor("#64748B"),
            "Failed": QColor("#DC2626"),
            "Cancelled": QColor("#7C3AED"),
        }.get(item.status_text, QColor("#94A3B8"))


class RemoveButtonDelegate(QStyledItemDelegate):
    remove_requested = Signal(int)

    def paint(self, painter, option, index):
        if index.column() != 4:
            return super().paint(painter, option, index)
        selected = bool(option.state & QStyle.StateFlag.State_Selected)
        hovered = bool(option.state & QStyle.StateFlag.State_MouseOver)
        button_rect = option.rect.adjusted(10, 7, -10, -7)

        if selected:
            fill = QColor("#DCEAFE")
            border = QColor("#93C5FD" if hovered else "#BFDBFE")
            text = QColor("#1D4ED8")
        else:
            fill = QColor("#FFFFFF" if not hovered else "#F8FAFC")
            border = QColor("#CBD5E1" if not hovered else "#93C5FD")
            text = QColor("#475569")

        painter.save()
        painter.setRenderHint(QPainter.RenderHint.Antialiasing, True)
        painter.setPen(QPen(border, 1))
        painter.setBrush(fill)
        painter.drawRoundedRect(button_rect, 10, 10)
        painter.setPen(text)
        painter.drawText(button_rect, Qt.AlignmentFlag.AlignCenter, "Remove")
        painter.restore()

    def editorEvent(self, event, model, option, index):
        if index.column() == 4 and event.type() == event.Type.MouseButtonRelease:
            self.remove_requested.emit(index.row())
            return True
        return super().editorEvent(event, model, option, index)


class StatusBadgeDelegate(QStyledItemDelegate):
    def paint(self, painter: QPainter, option, index) -> None:
        text = index.data(Qt.ItemDataRole.DisplayRole) or ""
        color = index.data(Qt.ItemDataRole.DecorationRole) or QColor("#94A3B8")
        painter.save()
        painter.setRenderHint(QPainter.RenderHint.Antialiasing, True)
        dot_rect = QRect(option.rect.left() + 12, option.rect.center().y() - 4, 8, 8)
        painter.setPen(Qt.PenStyle.NoPen)
        painter.setBrush(color)
        painter.drawEllipse(dot_rect)
        text_rect = option.rect.adjusted(28, 0, -8, 0)
        pen = option.palette.highlightedText().color() if option.state & QStyle.StateFlag.State_Selected else option.palette.text().color()
        painter.setPen(pen)
        painter.drawText(text_rect, Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter, text)
        painter.restore()


class FilenameBadgeDelegate(QStyledItemDelegate):
    def paint(self, painter: QPainter, option, index) -> None:
        text = index.data(Qt.ItemDataRole.DisplayRole) or ""
        item = index.model().item_at(index.row())
        color = index.model().status_color(item) if item is not None else QColor("#94A3B8")
        painter.save()
        painter.setRenderHint(QPainter.RenderHint.Antialiasing, True)
        dot_rect = QRect(option.rect.left() + 12, option.rect.center().y() - 4, 8, 8)
        painter.setPen(Qt.PenStyle.NoPen)
        painter.setBrush(color)
        painter.drawEllipse(dot_rect)
        text_rect = option.rect.adjusted(28, 0, -8, 0)
        pen = option.palette.highlightedText().color() if option.state & QStyle.StateFlag.State_Selected else option.palette.text().color()
        painter.setPen(pen)
        metrics = option.fontMetrics
        elided = metrics.elidedText(text, Qt.TextElideMode.ElideRight, max(32, text_rect.width()))
        painter.drawText(text_rect, Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter, elided)
        painter.restore()


class ImportedLogsTable(QTableView):
    remove_requested = Signal(int)

    def __init__(self, model: ImportedLogsTableModel) -> None:
        super().__init__()
        self.setModel(model)
        self.setObjectName("logTable")
        self.verticalHeader().hide()
        self.verticalHeader().setDefaultSectionSize(42)
        self.setAlternatingRowColors(False)
        self.setSelectionBehavior(QTableView.SelectionBehavior.SelectRows)
        self.setSelectionMode(QTableView.SelectionMode.SingleSelection)
        self.setSortingEnabled(False)
        self.setShowGrid(False)
        self.setWordWrap(False)
        self.setMouseTracking(True)
        self.setTextElideMode(Qt.TextElideMode.ElideRight)
        self.setColumnWidth(0, 360)
        self.setColumnWidth(1, 92)
        self.setColumnWidth(2, 104)
        self.setColumnWidth(3, 92)
        self.setColumnWidth(4, 82)
        self.horizontalHeader().setStretchLastSection(False)
        self.horizontalHeader().setSectionResizeMode(0, self.horizontalHeader().ResizeMode.Interactive)
        remove_delegate = RemoveButtonDelegate(self)
        remove_delegate.remove_requested.connect(self.remove_requested)
        self.setItemDelegateForColumn(0, FilenameBadgeDelegate(self))
        self.setItemDelegateForColumn(4, remove_delegate)
        self.setItemDelegateForColumn(2, StatusBadgeDelegate(self))
