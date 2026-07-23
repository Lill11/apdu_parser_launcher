from __future__ import annotations

import json
import subprocess
from pathlib import Path
from uuid import uuid4

from apdu_parser.core.parser_management_models import (
    LegacySourceInspectionResult,
    ManagedParserListResult,
    ParserActionResult,
    ParserTestResult,
    PluginValidationResult,
    parser_from_payload,
)
from apdu_parser.services.java_parser_service import JavaParserError
from apdu_parser.services.logging_service import LoggingService
from apdu_parser.services.path_service import PathService


class ParserManagementService:
    def __init__(self, path_service: PathService, logging_service: LoggingService) -> None:
        self.path_service = path_service
        self.logging_service = logging_service

    def list_parsers(self) -> ManagedParserListResult:
        payload = self._run_command(["--list-parsers"])
        return ManagedParserListResult(
            success=bool(payload.get("success", False)),
            message=str(payload.get("message", "")),
            parsers=[parser_from_payload(item) for item in payload.get("parsers", [])],
        )

    def validate_plugin(self, jar_path: Path) -> PluginValidationResult:
        payload = self._run_command(["--validate-plugin", str(jar_path)])
        return self._validation_from_payload(payload)

    def inspect_plugin(self, jar_path: Path) -> PluginValidationResult:
        payload = self._run_command(["--inspect-plugin", str(jar_path)])
        return self._validation_from_payload(payload)

    def inspect_legacy_source(self, source_path: Path) -> LegacySourceInspectionResult:
        payload = self._run_command(["--inspect-legacy-source", str(source_path)])
        source = payload.get("source", {})
        return LegacySourceInspectionResult(
            success=bool(payload.get("success", False)),
            message=str(payload.get("message", "")),
            status=str(payload.get("status", "")),
            diagnostics=[str(v) for v in payload.get("diagnostics", [])],
            package_name=str(source.get("packageName", "")),
            public_class_name=str(source.get("publicClassName", "")),
            main_class_name=str(source.get("mainClassName", "")),
        )

    def install_plugin(self, jar_path: Path) -> ParserActionResult:
        payload = self._run_command(["--install-plugin", str(jar_path)])
        return self._action_from_payload(payload)

    def install_source(self, source_path: Path) -> ParserActionResult:
        payload = self._run_command(["--install-source", str(source_path)])
        return self._action_from_payload(payload)

    def install_legacy_source(
        self,
        *,
        source_path: Path,
        parser_name: str,
        parser_id: str,
        version: str,
        supported_extensions: list[str],
        command_pattern: str,
        output_file_name: str,
        sample_input: Path,
    ) -> ParserActionResult:
        payload = self._run_command([
            "--install-legacy-source",
            str(source_path),
            "--parser-name",
            parser_name,
            "--parser-id",
            parser_id,
            "--parser-version",
            version,
            "--supported-extensions",
            ",".join(supported_extensions),
            "--legacy-command-pattern",
            command_pattern,
            "--legacy-output-file-name",
            output_file_name,
            "--sample-input",
            str(sample_input),
        ])
        return self._action_from_payload(payload)

    def enable_parser(self, parser_id: str) -> ParserActionResult:
        payload = self._run_command(["--enable-parser", parser_id])
        return self._action_from_payload(payload)

    def disable_parser(self, parser_id: str) -> ParserActionResult:
        payload = self._run_command(["--disable-parser", parser_id])
        return self._action_from_payload(payload)

    def remove_plugin(self, parser_id: str) -> ParserActionResult:
        payload = self._run_command(["--remove-plugin", parser_id])
        return self._action_from_payload(payload)

    def recompile_parser(self, parser_id: str) -> ParserActionResult:
        payload = self._run_command(["--recompile-parser", parser_id])
        return self._action_from_payload(payload)

    def test_parser(self, parser_id: str, input_path: Path) -> ParserTestResult:
        payload = self._run_command(["--test-parser", parser_id, "--input", str(input_path)])
        summary = payload.get("summary", {})
        detection = payload.get("detection", {})
        return ParserTestResult(
            success=bool(payload.get("success", False)),
            message=str(payload.get("message", "")),
            parser=parser_from_payload(payload["parser"]) if payload.get("parser") else None,
            matched=bool(detection.get("matched", False)),
            confidence=int(detection.get("confidence", 0)),
            reason=str(detection.get("reason", "")),
            status=str(summary.get("status", "")),
            apdu_count=int(summary.get("apduCount", 0)),
            warning_count=int(summary.get("warningCount", 0)),
            error_count=int(summary.get("errorCount", 0)),
            elapsed_ms=int(summary.get("elapsedMs", 0)),
            exit_code=int(summary.get("exitCode", 0)),
            stdout=str(payload.get("stdout", "")),
            stderr=str(payload.get("stderr", "")),
            output_path=str(payload.get("outputPath", "")),
            warnings=[str(v) for v in payload.get("warnings", [])],
            errors=[str(v) for v in payload.get("errors", [])],
        )

    def _action_from_payload(self, payload: dict) -> ParserActionResult:
        return ParserActionResult(
            success=bool(payload.get("success", False)),
            message=str(payload.get("message", "")),
            parser=parser_from_payload(payload["parser"]) if payload.get("parser") else None,
            status=str(payload.get("status", "")),
            diagnostics=[str(v) for v in payload.get("diagnostics", [])],
            compile_log=str(payload.get("compileLog", "")),
            compile_log_path=str(payload.get("compileLogPath", "")),
            compiler=dict(payload.get("compiler", {})) if isinstance(payload.get("compiler"), dict) else {},
            stdout=str(payload.get("stdout", "")),
            stderr=str(payload.get("stderr", "")),
            generated_output_path=str(payload.get("generatedOutputPath", "")),
            apdu_count=int(payload.get("apduCount", 0)),
            warnings=[str(v) for v in payload.get("warnings", [])],
        )

    def _validation_from_payload(self, payload: dict) -> PluginValidationResult:
        return PluginValidationResult(
            success=bool(payload.get("success", False)),
            message=str(payload.get("message", "")),
            status=str(payload.get("status", "")),
            inspected_jar=str(payload.get("inspectedJar", "")),
            validated_at=str(payload.get("validatedAt", "")),
            diagnostics=[str(v) for v in payload.get("diagnostics", [])],
            parser=parser_from_payload(payload["parser"]) if payload.get("parser") else None,
        )

    def _run_command(self, args: list[str]) -> dict:
        parser_paths = self.path_service.resolve_parser_paths()
        self.path_service.temp_root.mkdir(parents=True, exist_ok=True)
        output_path = self.path_service.temp_root / f"parser-management-{uuid4().hex}.json"
        request_path = self.path_service.temp_root / f"parser-management-request-{uuid4().hex}.json"
        request_payload = self._build_request_payload(args, output_path)
        request_path.write_text(json.dumps(request_payload, ensure_ascii=False), encoding="utf-8")
        command = [
            str(parser_paths.java_executable),
            "-Dfile.encoding=UTF-8",
            "-jar",
            str(parser_paths.parser_jar),
            "--request-file",
            str(request_path),
        ]
        try:
            completed = subprocess.run(
                command,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                stdin=subprocess.DEVNULL,
                text=True,
                encoding="utf-8",
                errors="replace",
                shell=False,
                check=False,
            )
        except OSError as exc:
            raise JavaParserError(f"Java process failed to start: {exc}", stderr=str(exc)) from exc
        try:
            if completed.stdout:
                self.logging_service.write_diagnostic("parser-admin-stdout", completed.stdout)
            if completed.stderr:
                self.logging_service.write_diagnostic("parser-admin-stderr", completed.stderr)
            if not output_path.exists():
                raise JavaParserError("Parser management command did not produce a JSON result file.", exit_code=completed.returncode, stderr=completed.stderr)
            payload = json.loads(output_path.read_text(encoding="utf-8"))
            if completed.returncode != 0:
                raise JavaParserError(str(payload.get("message", "Parser management command failed.")), exit_code=completed.returncode, stderr=completed.stderr)
            return payload
        finally:
            output_path.unlink(missing_ok=True)
            request_path.unlink(missing_ok=True)

    @staticmethod
    def _build_request_payload(args: list[str], output_path: Path) -> dict:
        payload: dict[str, str] = {
            "mode": "listParsers",
            "jsonOut": ParserManagementService._serialize_path(output_path),
        }
        if not args:
            return payload

        flag = args[0]
        if flag == "--list-parsers":
            payload["mode"] = "listParsers"
        elif flag == "--validate-plugin":
            payload["mode"] = "validatePlugin"
            payload["pluginJar"] = ParserManagementService._serialize_path(Path(args[1]))
        elif flag == "--inspect-plugin":
            payload["mode"] = "inspectPlugin"
            payload["pluginJar"] = ParserManagementService._serialize_path(Path(args[1]))
        elif flag == "--inspect-legacy-source":
            payload["mode"] = "inspectLegacySource"
            payload["sourceFile"] = ParserManagementService._serialize_path(Path(args[1]))
        elif flag == "--install-plugin":
            payload["mode"] = "installPlugin"
            payload["pluginJar"] = ParserManagementService._serialize_path(Path(args[1]))
        elif flag == "--install-source":
            payload["mode"] = "installSource"
            payload["sourceFile"] = ParserManagementService._serialize_path(Path(args[1]))
        elif flag == "--install-legacy-source":
            payload["mode"] = "installLegacySource"
            payload["sourceFile"] = ParserManagementService._serialize_path(Path(args[1]))
            payload["parserName"] = args[3]
            payload["parserId"] = args[5]
            payload["parserVersion"] = args[7]
            payload["supportedExtensionsCsv"] = args[9]
            payload["legacyCommandPattern"] = args[11]
            payload["legacyOutputFileName"] = args[13]
            payload["sampleInput"] = ParserManagementService._serialize_path(Path(args[15]))
        elif flag == "--enable-parser":
            payload["mode"] = "enableParser"
            payload["parserId"] = args[1]
        elif flag == "--disable-parser":
            payload["mode"] = "disableParser"
            payload["parserId"] = args[1]
        elif flag == "--remove-plugin":
            payload["mode"] = "removePlugin"
            payload["parserId"] = args[1]
        elif flag == "--recompile-parser":
            payload["mode"] = "recompileParser"
            payload["parserId"] = args[1]
        elif flag == "--test-parser":
            payload["mode"] = "testParser"
            payload["parserId"] = args[1]
            payload["input"] = ParserManagementService._serialize_path(Path(args[3]))
        else:
            raise ValueError(f"Unsupported parser-management command: {flag}")
        return payload

    @staticmethod
    def _serialize_path(path: Path) -> str:
        return path.resolve().as_posix()
