from __future__ import annotations

import json
from pathlib import Path

import pytest

from apdu_parser.services.java_parser_service import JavaParserError
from apdu_parser.services.logging_service import LoggingService
from apdu_parser.services.parser_management_service import ParserManagementService
from apdu_parser.services.path_service import PathService


def make_service(tmp_path: Path, monkeypatch) -> ParserManagementService:
    project = tmp_path / "project"
    (project / "parser").mkdir(parents=True)
    (project / "parser" / "apdu-parser.jar").write_text("stub", encoding="utf-8")
    (project / "runtime" / "bin").mkdir(parents=True)
    (project / "runtime" / "bin" / "java.exe").write_text("stub", encoding="utf-8")
    monkeypatch.setenv("APDU_PARSER_DATA_ROOT", str(tmp_path / "appdata"))
    path_service = PathService(project_root=project)
    return ParserManagementService(path_service=path_service, logging_service=LoggingService(path_service))


def test_list_parsers_maps_payload(tmp_path, monkeypatch):
    service = make_service(tmp_path, monkeypatch)

    def fake_run_command(_args):
        return {
            "success": True,
            "message": "Loaded parsers.",
            "parsers": [
                {
                    "name": "Built-in Parser",
                    "id": "builtin",
                    "version": "1.0.0",
                    "pluginApiVersion": 1,
                    "supportedExtensions": [".log"],
                    "sourceType": "BUILT_IN",
                    "enabled": True,
                    "validationStatus": "COMPATIBLE",
                    "validationMessage": "Compatible",
                    "installDirectory": "",
                    "pluginJar": "",
                    "implementationClass": "BuiltinParser",
                    "builtIn": True,
                    "preservedSourceFile": "",
                    "originalSourcePath": "",
                    "compileLogPath": "",
                    "legacyMainClass": "",
                    "legacyCommandPattern": "",
                    "legacyOutputFileName": "",
                    "lastCompiledAt": "",
                    "lastCompilationStatus": "",
                    "lastCompilationMessage": "",
                    "lastTestedAt": "",
                    "lastTestStatus": "",
                    "lastTestMessage": "",
                    "lastTestStderr": "",
                    "lastValidatedAt": "",
                    "installedAt": "",
                    "priority": 100,
                }
            ],
        }

    monkeypatch.setattr(service, "_run_command", fake_run_command)
    result = service.list_parsers()
    assert result.success
    assert result.parsers[0].parser_id == "builtin"
    assert result.parsers[0].built_in


def test_run_command_raises_when_json_missing(tmp_path, monkeypatch):
    service = make_service(tmp_path, monkeypatch)

    class DummyCompleted:
        returncode = 0
        stdout = ""
        stderr = ""

    monkeypatch.setattr("subprocess.run", lambda *a, **k: DummyCompleted())

    with pytest.raises(JavaParserError, match="did not produce a JSON result file"):
        service._run_command(["--list-parsers"])


def test_run_command_raises_for_nonzero_exit_code(tmp_path, monkeypatch):
    service = make_service(tmp_path, monkeypatch)
    service.path_service.temp_root.mkdir(parents=True, exist_ok=True)

    class DummyCompleted:
        returncode = 3
        stdout = ""
        stderr = "boom"

    def fake_run(*args, **kwargs):
        command = args[0]
        request_file = Path(command[-1])
        payload = json.loads(request_file.read_text(encoding="utf-8"))
        target = Path(payload["jsonOut"])
        target.write_text(json.dumps({"message": "Plugin failed"}), encoding="utf-8")
        return DummyCompleted()

    monkeypatch.setattr("subprocess.run", fake_run)

    with pytest.raises(JavaParserError, match="Plugin failed"):
        service._run_command(["--list-parsers"])


def test_build_request_payload_for_test_parser(tmp_path, monkeypatch):
    service = make_service(tmp_path, monkeypatch)
    output_path = service.path_service.temp_root / "result.json"
    payload = service._build_request_payload(
        ["--test-parser", "sample_pcsc_plugin", "--input", r"C:\logs\日志 español sample.log"],
        output_path,
    )

    assert payload["mode"] == "testParser"
    assert payload["parserId"] == "sample_pcsc_plugin"
    assert payload["input"].endswith("日志 español sample.log")
    assert payload["jsonOut"] == output_path.resolve().as_posix()


def test_build_request_payload_for_install_source(tmp_path, monkeypatch):
    service = make_service(tmp_path, monkeypatch)
    output_path = service.path_service.temp_root / "result.json"
    payload = service._build_request_payload(
        ["--install-source", r"C:\plugins\SourcePcscPlugin.java"],
        output_path,
    )

    assert payload["mode"] == "installSource"
    assert payload["sourceFile"].endswith("SourcePcscPlugin.java")


def test_build_request_payload_for_install_legacy_source(tmp_path, monkeypatch):
    service = make_service(tmp_path, monkeypatch)
    output_path = service.path_service.temp_root / "result.json"
    payload = service._build_request_payload(
        [
            "--install-legacy-source",
            r"C:\plugins\LegacyPcscExtractor.java",
            "--parser-name",
            "Legacy PCSC",
            "--parser-id",
            "legacy_pcsc",
            "--parser-version",
            "1.0.0",
            "--supported-extensions",
            ".txt,.log",
            "--legacy-command-pattern",
            "INPUT_FILE_OUTPUT_FILE",
            "--legacy-output-file-name",
            "apdus.txt",
            "--sample-input",
            r"C:\logs\sample legacy.txt",
        ],
        output_path,
    )

    assert payload["mode"] == "installLegacySource"
    assert payload["sourceFile"].endswith("LegacyPcscExtractor.java")
    assert payload["parserName"] == "Legacy PCSC"
    assert payload["parserId"] == "legacy_pcsc"
    assert payload["supportedExtensionsCsv"] == ".txt,.log"
    assert payload["sampleInput"].endswith("sample legacy.txt")
