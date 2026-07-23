from __future__ import annotations

from pathlib import Path

import pytest

from apdu_parser.services.java_parser_service import JavaParserError, JavaParserService, JobHandle
from apdu_parser.services.logging_service import LoggingService
from apdu_parser.services.path_service import PathService


def make_service(tmp_path: Path, monkeypatch) -> JavaParserService:
    project = tmp_path / "project"
    (project / "parser").mkdir(parents=True)
    (project / "parser" / "apdu-parser.jar").write_text("stub", encoding="utf-8")
    (project / "runtime" / "bin").mkdir(parents=True)
    (project / "runtime" / "bin" / "java.exe").write_text("stub", encoding="utf-8")
    monkeypatch.setenv("APDU_PARSER_DATA_ROOT", str(tmp_path / "appdata"))
    path_service = PathService(project_root=project)
    return JavaParserService(path_service=path_service, logging_service=LoggingService(path_service))


def test_build_command_contains_argument_array(tmp_path, monkeypatch):
    service = make_service(tmp_path, monkeypatch)
    cmd = service.build_command(request_file=Path("C:/temp/request.json"))
    assert cmd[0].endswith("java.exe")
    assert "--request-file" in cmd
    assert str(Path("C:/temp/request.json")) in cmd
    assert "-jar" in cmd


def test_missing_jar_raises(tmp_path, monkeypatch):
    monkeypatch.setenv("APDU_PARSER_DATA_ROOT", str(tmp_path / "appdata"))
    path_service = PathService(project_root=tmp_path / "missing")
    service = JavaParserService(path_service=path_service, logging_service=LoggingService(path_service))
    with pytest.raises(FileNotFoundError):
        service.build_command(request_file=Path("C:/request.json"))


def test_invalid_json_raises(tmp_path, monkeypatch):
    service = make_service(tmp_path, monkeypatch)
    out_json = tmp_path / "result.json"
    artifacts = tmp_path / "artifacts"
    source = tmp_path / "sample.log"
    source.write_text("dummy", encoding="utf-8")

    class DummyProc:
        def __init__(self):
            self.returncode = 0

        def poll(self):
            return 0

        def communicate(self):
            out_json.write_text("{ invalid", encoding="utf-8")
            return "", ""

    monkeypatch.setattr("subprocess.Popen", lambda *a, **k: DummyProc())
    with pytest.raises(JavaParserError):
        service.run_parser(
            input_path=source,
            json_output_path=out_json,
            artifacts_dir=artifacts,
            timeout_seconds=1,
        )


def test_failed_process_start_raises_java_parser_error(tmp_path, monkeypatch):
    service = make_service(tmp_path, monkeypatch)
    source = tmp_path / "sample.log"
    out_json = tmp_path / "result.json"
    artifacts = tmp_path / "artifacts"
    source.write_text("dummy", encoding="utf-8")

    def raise_start_error(*args, **kwargs):
        raise OSError("cannot start java")

    monkeypatch.setattr("subprocess.Popen", raise_start_error)

    with pytest.raises(JavaParserError, match="failed to start"):
        service.run_parser(
            input_path=source,
            json_output_path=out_json,
            artifacts_dir=artifacts,
            timeout_seconds=1,
        )


def test_timeout_kills_process_and_raises(tmp_path, monkeypatch):
    service = make_service(tmp_path, monkeypatch)
    source = tmp_path / "sample.log"
    out_json = tmp_path / "result.json"
    artifacts = tmp_path / "artifacts"
    source.write_text("dummy", encoding="utf-8")

    class DummyProc:
        def __init__(self):
            self.returncode = None
            self.killed = False

        def poll(self):
            return None

        def kill(self):
            self.killed = True
            self.returncode = -9

        def communicate(self):
            return "", ""

    proc = DummyProc()
    ticks = iter([0.0, 0.0, 5.0, 5.0])

    monkeypatch.setattr("subprocess.Popen", lambda *a, **k: proc)
    monkeypatch.setattr("time.monotonic", lambda: next(ticks))
    monkeypatch.setattr("time.sleep", lambda *_: None)

    with pytest.raises(JavaParserError, match="timed out"):
        service.run_parser(
            input_path=source,
            json_output_path=out_json,
            artifacts_dir=artifacts,
            timeout_seconds=1,
        )
    assert proc.killed


def test_cancellation_kills_process_and_raises(tmp_path, monkeypatch):
    service = make_service(tmp_path, monkeypatch)
    source = tmp_path / "sample.log"
    out_json = tmp_path / "result.json"
    artifacts = tmp_path / "artifacts"
    source.write_text("dummy", encoding="utf-8")

    class DummyProc:
        def __init__(self):
            self.returncode = None
            self.killed = False

        def poll(self):
            return None

        def kill(self):
            self.killed = True
            self.returncode = -9

        def communicate(self):
            return "", ""

    handle = JobHandle(cancelled=True)
    proc = DummyProc()

    monkeypatch.setattr("subprocess.Popen", lambda *a, **k: proc)
    monkeypatch.setattr("time.sleep", lambda *_: None)

    with pytest.raises(JavaParserError, match="cancelled"):
        service.run_parser(
            input_path=source,
            json_output_path=out_json,
            artifacts_dir=artifacts,
            timeout_seconds=5,
            handle=handle,
        )
    assert proc.killed
