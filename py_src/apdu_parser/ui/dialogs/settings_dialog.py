from __future__ import annotations

from PySide6.QtWidgets import QCheckBox, QDialog, QDialogButtonBox, QFormLayout, QSpinBox, QVBoxLayout

from apdu_parser.services.config_service import UiSettings


class SettingsDialog(QDialog):
    def __init__(self, settings: UiSettings, parent=None) -> None:
        super().__init__(parent)
        self.setWindowTitle("Settings")
        self.timeout_spin = QSpinBox()
        self.timeout_spin.setRange(5, 600)
        self.timeout_spin.setValue(settings.parser_timeout_seconds)
        self.parallel_spin = QSpinBox()
        self.parallel_spin.setRange(1, 8)
        self.parallel_spin.setValue(settings.max_parallel_jobs)
        self.retain_checkbox = QCheckBox("Retain temporary files")
        self.retain_checkbox.setChecked(settings.retain_temporary_files)

        form = QFormLayout()
        form.addRow("Parser timeout (seconds)", self.timeout_spin)
        form.addRow("Maximum parallel jobs", self.parallel_spin)
        form.addRow("", self.retain_checkbox)

        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)

        layout = QVBoxLayout(self)
        layout.addLayout(form)
        layout.addWidget(buttons)

    def apply_to(self, settings: UiSettings) -> UiSettings:
        settings.parser_timeout_seconds = self.timeout_spin.value()
        settings.max_parallel_jobs = self.parallel_spin.value()
        settings.retain_temporary_files = self.retain_checkbox.isChecked()
        return settings
