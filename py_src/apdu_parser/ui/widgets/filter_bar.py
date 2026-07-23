from __future__ import annotations

from PySide6.QtCore import Signal
from PySide6.QtWidgets import QButtonGroup, QHBoxLayout, QPushButton, QWidget

from apdu_parser.core.models import FilterMode


class FilterBar(QWidget):
    filter_changed = Signal(str)

    def __init__(self) -> None:
        super().__init__()
        layout = QHBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(8)
        self.group = QButtonGroup(self)
        self.group.setExclusive(True)
        self.buttons: dict[FilterMode, QPushButton] = {}

        for mode in FilterMode:
            button = QPushButton(mode.value)
            button.setCheckable(True)
            button.setObjectName("filterChip")
            layout.addWidget(button)
            self.group.addButton(button)
            self.buttons[mode] = button
            button.clicked.connect(lambda checked=False, m=mode: self.filter_changed.emit(m.value))

        layout.addStretch(1)
        self.group.buttonToggled.connect(self._refresh_styles)
        self.set_mode(FilterMode.ALL)

    def set_mode(self, mode: FilterMode) -> None:
        for candidate, button in self.buttons.items():
            button.setChecked(candidate == mode)
        self._refresh_styles()

    def _refresh_styles(self, *_args) -> None:
        for button in self.buttons.values():
            button.style().unpolish(button)
            button.style().polish(button)
