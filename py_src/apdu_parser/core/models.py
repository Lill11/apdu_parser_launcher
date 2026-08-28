from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any


class LogStatus(str, Enum):
    PENDING = "Pending"
    DETECTING = "Detecting"
    ANALYZING = "Analyzing"
    COMPLETED = "Completed"
    UNSUPPORTED = "Unsupported"
    FAILED = "Failed"
    CANCELLED = "Cancelled"


class FilterMode(str, Enum):
    ALL = "All"
    ES10 = "ES10"
    FETCH_TR = "FETCH/TR"
    LSI = "LSI"


@dataclass(slots=True)
class ParserPaths:
    java_executable: Path
    javac_executable: Path
    jar_executable: Path
    parser_jar: Path


@dataclass(slots=True)
class OutputFiles:
    json: Path | None
    artifacts_dir: Path | None
    apdu_text: Path | None
    analysis_text: Path | None
    errors_text: Path | None
    legacy_result_json: Path | None
    stderr_log: str = ""
    java_text: Path | None = None


@dataclass(slots=True)
class DetectedParser:
    parser_id: str
    display_name: str
    supported: bool


@dataclass(slots=True)
class ApduRow:
    index: int | None
    command: str
    response: str
    command_name: str
    headline: str
    status_word: str
    severity: str
    tag: str
    source_line: int
    filters: list[str] = field(default_factory=list)
    note: str = ""
    event_sequence: int = 0
    event_type: str = "APDU"
    reset_type: str = ""
    atr: str = ""

    @property
    def category(self) -> str:
        if self.event_type == "RESET":
            return "SYSTEM"
        if "ES10" in self.filters:
            return "ES10"
        if "FETCH/TR" in self.filters:
            return "FETCH/TR"
        if "LSI" in self.filters:
            return "LSI"
        return "General"

    @property
    def description(self) -> str:
        if self.event_type == "RESET":
            return "Cold Reset"
        return self.headline or self.command_name


@dataclass(slots=True)
class AnalysisEvent:
    index: int
    event_type: str
    title: str
    message: str
    severity: str
    status_word: str
    tag: str
    source_line: int
    event_sequence: int = 0
    reset_type: str = ""
    atr: str = ""


@dataclass(slots=True)
class AppletFile:
    name: str
    lines: list[str]


@dataclass(slots=True)
class AppletPayload:
    status: str
    message: str
    all_clean: list[str]
    files: list[AppletFile]


@dataclass(slots=True)
class ErrorPayload:
    code: str
    message: str
    details: str


@dataclass(slots=True)
class ParserSummary:
    apdu_count: int
    analysis_event_count: int
    applet_count: int
    warning_count: int
    exit_code: int


@dataclass(slots=True)
class ApduStep:
    command: str
    expected_status_words: list[str] = field(default_factory=list)
    expected_status_expression: str = ""
    source_line: int = 0


@dataclass(slots=True)
class ParseResult:
    schema_version: int
    parser_version: str
    success: bool
    status: str
    message: str
    generated_at: str
    source_file: Path
    source_file_name: str
    detected_parser: DetectedParser
    summary: ParserSummary
    apdus: list[ApduRow]
    events: list[ApduRow]
    analysis: list[AnalysisEvent]
    applets: AppletPayload
    warnings: list[str]
    errors: list[ErrorPayload]
    output_files: OutputFiles
    raw: dict[str, Any]
    apdu_steps: list[ApduStep] = field(default_factory=list)
    generated_java: str = ""
    generated_java_class_name: str = ""


@dataclass(slots=True)
class ImportedLogItem:
    item_id: str
    source_path: Path
    status: LogStatus = LogStatus.PENDING
    detected_format: str = "Pending"
    result_summary: str = ""
    result: ParseResult | None = None
    error_message: str = ""
    working_dir: Path | None = None
    output_json_path: Path | None = None
    artifacts_dir: Path | None = None
    active_job_id: str | None = None

    @property
    def file_name(self) -> str:
        return self.source_path.name

    @property
    def status_text(self) -> str:
        return self.status.value
