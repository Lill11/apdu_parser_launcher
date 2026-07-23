from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path


@dataclass(slots=True)
class ManagedParser:
    name: str
    parser_id: str
    version: str
    plugin_api_version: int
    supported_extensions: list[str]
    source_type: str
    enabled: bool
    validation_status: str
    validation_message: str
    install_directory: str
    plugin_jar: str
    implementation_class: str
    built_in: bool
    preserved_source_file: str
    original_source_path: str
    compile_log_path: str
    legacy_main_class: str
    legacy_command_pattern: str
    legacy_output_file_name: str
    last_compiled_at: str
    last_compilation_status: str
    last_compilation_message: str
    last_tested_at: str
    last_test_status: str
    last_test_message: str
    last_test_stderr: str
    last_validated_at: str
    installed_at: str
    priority: int


@dataclass(slots=True)
class ManagedParserListResult:
    success: bool
    message: str
    parsers: list[ManagedParser] = field(default_factory=list)


@dataclass(slots=True)
class PluginValidationResult:
    success: bool
    message: str
    status: str
    inspected_jar: str
    validated_at: str
    diagnostics: list[str] = field(default_factory=list)
    parser: ManagedParser | None = None


@dataclass(slots=True)
class LegacySourceInspectionResult:
    success: bool
    message: str
    status: str
    diagnostics: list[str] = field(default_factory=list)
    package_name: str = ""
    public_class_name: str = ""
    main_class_name: str = ""


@dataclass(slots=True)
class ParserActionResult:
    success: bool
    message: str
    parser: ManagedParser | None = None
    status: str = ""
    diagnostics: list[str] = field(default_factory=list)
    compile_log: str = ""
    compile_log_path: str = ""
    compiler: dict[str, str | bool] = field(default_factory=dict)
    stdout: str = ""
    stderr: str = ""
    generated_output_path: str = ""
    apdu_count: int = 0
    warnings: list[str] = field(default_factory=list)


@dataclass(slots=True)
class ParserTestResult:
    success: bool
    message: str
    parser: ManagedParser | None
    matched: bool
    confidence: int
    reason: str
    status: str
    apdu_count: int
    warning_count: int
    error_count: int
    elapsed_ms: int
    exit_code: int = 0
    stdout: str = ""
    stderr: str = ""
    output_path: str = ""
    warnings: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)


def parser_from_payload(payload: dict) -> ManagedParser:
    return ManagedParser(
        name=str(payload.get("name", "")),
        parser_id=str(payload.get("id", "")),
        version=str(payload.get("version", "")),
        plugin_api_version=int(payload.get("pluginApiVersion", 0)),
        supported_extensions=[str(v) for v in payload.get("supportedExtensions", [])],
        source_type=str(payload.get("sourceType", "")),
        enabled=bool(payload.get("enabled", False)),
        validation_status=str(payload.get("validationStatus", "")),
        validation_message=str(payload.get("validationMessage", "")),
        install_directory=str(payload.get("installDirectory", "")),
        plugin_jar=str(payload.get("pluginJar", "")),
        implementation_class=str(payload.get("implementationClass", "")),
        built_in=bool(payload.get("builtIn", False)),
        preserved_source_file=str(payload.get("preservedSourceFile", "")),
        original_source_path=str(payload.get("originalSourcePath", "")),
        compile_log_path=str(payload.get("compileLogPath", "")),
        legacy_main_class=str(payload.get("legacyMainClass", "")),
        legacy_command_pattern=str(payload.get("legacyCommandPattern", "")),
        legacy_output_file_name=str(payload.get("legacyOutputFileName", "")),
        last_compiled_at=str(payload.get("lastCompiledAt", "")),
        last_compilation_status=str(payload.get("lastCompilationStatus", "")),
        last_compilation_message=str(payload.get("lastCompilationMessage", "")),
        last_tested_at=str(payload.get("lastTestedAt", "")),
        last_test_status=str(payload.get("lastTestStatus", "")),
        last_test_message=str(payload.get("lastTestMessage", "")),
        last_test_stderr=str(payload.get("lastTestStderr", "")),
        last_validated_at=str(payload.get("lastValidatedAt", "")),
        installed_at=str(payload.get("installedAt", "")),
        priority=int(payload.get("priority", 0)),
    )
