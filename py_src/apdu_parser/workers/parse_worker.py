from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from PySide6.QtCore import QObject, QRunnable, Signal

from apdu_parser.core.models import ParseResult
from apdu_parser.services.java_parser_service import JavaParserError, JavaParserService, JobHandle


@dataclass(slots=True)
class ParseRequest:
    item_id: str
    input_path: Path
    json_output_path: Path
    artifacts_dir: Path
    timeout_seconds: int
    detect_only: bool = False


class ParseWorkerSignals(QObject):
    started = Signal(str)
    finished = Signal(str, object)
    failed = Signal(str, str, int, str)
    cancelled = Signal(str)


class ParseWorker(QRunnable):
    def __init__(self, service: JavaParserService, request: ParseRequest, handle: JobHandle) -> None:
        super().__init__()
        self.service = service
        self.request = request
        self.handle = handle
        self.signals = ParseWorkerSignals()
        self.setAutoDelete(True)

    def run(self) -> None:
        self.signals.started.emit(self.request.item_id)
        try:
            result = self.service.run_parser(
                input_path=self.request.input_path,
                json_output_path=self.request.json_output_path,
                artifacts_dir=self.request.artifacts_dir,
                timeout_seconds=self.request.timeout_seconds,
                detect_only=self.request.detect_only,
                handle=self.handle,
            )
            self.signals.finished.emit(self.request.item_id, result)
        except JavaParserError as exc:
            if self.handle.cancelled:
                self.signals.cancelled.emit(self.request.item_id)
            else:
                self.signals.failed.emit(
                    self.request.item_id,
                    str(exc),
                    -1 if exc.exit_code is None else int(exc.exit_code),
                    exc.stderr,
                )
        except Exception as exc:  # pragma: no cover - unexpected safety net
            self.signals.failed.emit(self.request.item_id, str(exc), -1, "")
