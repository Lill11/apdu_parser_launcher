from __future__ import annotations

import subprocess
import threading
import time
import json
from dataclasses import dataclass, field
from pathlib import Path
from uuid import uuid4

from apdu_parser.core.models import ParseResult
from apdu_parser.core.result_mapper import ResultMappingError, map_result_file
from apdu_parser.services.logging_service import LoggingService
from apdu_parser.services.path_service import PathService


class JavaParserError(RuntimeError):
    def __init__(self, message: str, *, exit_code: int | None = None, stderr: str = "") -> None:
        super().__init__(message)
        self.exit_code = exit_code
        self.stderr = stderr


@dataclass(slots=True)
class JobHandle:
    process: subprocess.Popen[str] | None = None
    cancelled: bool = False
    lock: threading.Lock = field(default_factory=threading.Lock)

    def cancel(self) -> None:
        with self.lock:
            self.cancelled = True
            if self.process and self.process.poll() is None:
                self.process.kill()


class JavaParserService:
    def __init__(self, path_service: PathService, logging_service: LoggingService) -> None:
        self.path_service = path_service
        self.logging_service = logging_service

    def build_command(
        self,
        *,
        request_file: Path,
    ) -> list[str]:
        parser_paths = self.path_service.resolve_parser_paths()
        return [
            str(parser_paths.java_executable),
            "-Dfile.encoding=UTF-8",
            "-jar",
            str(parser_paths.parser_jar),
            "--request-file",
            str(request_file),
        ]

    def run_parser(
        self,
        *,
        input_path: Path,
        json_output_path: Path,
        artifacts_dir: Path,
        timeout_seconds: int,
        detect_only: bool = False,
        handle: JobHandle | None = None,
    ) -> ParseResult:
        self.path_service.temp_root.mkdir(parents=True, exist_ok=True)
        json_output_path.parent.mkdir(parents=True, exist_ok=True)
        artifacts_dir.mkdir(parents=True, exist_ok=True)
        request_file = self.path_service.temp_root / f"parse-request-{uuid4().hex}.json"
        request_payload = {
            "input": str(input_path),
            "jsonOut": str(json_output_path),
            "artifactsDir": str(artifacts_dir),
            "detectOnly": "true" if detect_only else "false",
        }
        request_file.write_text(json.dumps(request_payload, ensure_ascii=False), encoding="utf-8")
        command = self.build_command(request_file=request_file)

        try:
            try:
                proc = subprocess.Popen(
                    command,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    stdin=subprocess.DEVNULL,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                    shell=False,
                )
            except OSError as exc:
                raise JavaParserError(f"Java process failed to start: {exc}", stderr=str(exc)) from exc
            if handle is not None:
                with handle.lock:
                    handle.process = proc
                    if handle.cancelled and proc.poll() is None:
                        proc.kill()

            started = time.monotonic()
            while proc.poll() is None:
                if handle is not None and handle.cancelled:
                    proc.kill()
                    raise JavaParserError("Analysis cancelled.", stderr="")
                if time.monotonic() - started > timeout_seconds:
                    proc.kill()
                    raise JavaParserError(f"Parser timed out after {timeout_seconds} seconds.", exit_code=None, stderr="")
                time.sleep(0.05)

            stdout, stderr = proc.communicate()
            if stdout:
                self.logging_service.write_diagnostic("java-stdout", stdout)
            if stderr:
                self.logging_service.write_diagnostic("java-stderr", stderr)

            if not json_output_path.exists():
                raise JavaParserError("Parser did not produce a JSON result file.", exit_code=proc.returncode, stderr=stderr)

            try:
                result = map_result_file(json_output_path)
            except ResultMappingError as exc:
                raise JavaParserError(str(exc), exit_code=proc.returncode, stderr=stderr) from exc

            if proc.returncode not in (0, 1, 2, 3, 4, 5):
                raise JavaParserError(f"Unexpected parser exit code: {proc.returncode}", exit_code=proc.returncode, stderr=stderr)

            if result.summary.exit_code != proc.returncode:
                raise JavaParserError(
                    f"Parser JSON exit code mismatch. process={proc.returncode} json={result.summary.exit_code}",
                    exit_code=proc.returncode,
                    stderr=stderr,
                )

            return result
        finally:
            if request_file.exists():
                request_file.unlink(missing_ok=True)
