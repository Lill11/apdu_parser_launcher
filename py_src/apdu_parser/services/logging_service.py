from __future__ import annotations

from datetime import datetime, UTC
from pathlib import Path

from apdu_parser.services.path_service import PathService


class LoggingService:
    def __init__(self, path_service: PathService) -> None:
        self.path_service = path_service
        self.path_service.ensure_layout()

    def write_diagnostic(self, name: str, content: str) -> Path:
        timestamp = datetime.now(UTC).strftime("%Y%m%d-%H%M%S-%f")
        path = self.path_service.diagnostics_root / f"{timestamp}-{name}.log"
        path.write_text(content, encoding="utf-8")
        return path
