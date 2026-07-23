from __future__ import annotations

import sys
from pathlib import Path

from apdu_parser.services.path_service import PathService


def test_path_service_uses_localappdata(monkeypatch, tmp_path):
    monkeypatch.delenv("APDU_PARSER_DATA_ROOT", raising=False)
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path / "LocalAppData"))
    service = PathService(project_root=Path("C:/repo/apdu_parser_launcher"))
    assert service.app_data_root == tmp_path / "LocalAppData" / "APDUParser"
    assert service.settings_path.name == "ui-settings.json"


def test_path_service_prefers_project_runtime(tmp_path):
    project = tmp_path / "project"
    runtime_java = project / "runtime" / "bin" / "java.exe"
    runtime_javac = project / "runtime" / "bin" / "javac.exe"
    runtime_jar = project / "runtime" / "bin" / "jar.exe"
    parser_jar = project / "parser" / "apdu-parser.jar"
    runtime_java.parent.mkdir(parents=True)
    parser_jar.parent.mkdir(parents=True)
    runtime_java.write_text("stub", encoding="utf-8")
    runtime_javac.write_text("stub", encoding="utf-8")
    runtime_jar.write_text("stub", encoding="utf-8")
    parser_jar.write_text("stub", encoding="utf-8")
    service = PathService(project_root=project)
    paths = service.resolve_parser_paths()
    assert paths.java_executable == runtime_java
    assert paths.javac_executable == runtime_javac
    assert paths.jar_executable == runtime_jar
    assert paths.parser_jar == parser_jar


def test_frozen_path_service_requires_bundled_runtime(tmp_path, monkeypatch):
    frozen_root = tmp_path / "dist" / "APDUParser"
    bundled_resources = frozen_root / "_internal" / "resources"
    parser_jar = frozen_root / "parser" / "apdu-parser.jar"
    parser_jar.parent.mkdir(parents=True)
    bundled_resources.mkdir(parents=True)
    parser_jar.write_text("stub", encoding="utf-8")
    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "executable", str(frozen_root / "APDUParser.exe"), raising=False)

    service = PathService()
    assert service.project_root == frozen_root
    assert service.resource_root == bundled_resources

    try:
        service.resolve_parser_paths()
    except FileNotFoundError as exc:
        assert "Bundled Java toolchain is incomplete" in str(exc)
    else:  # pragma: no cover
        raise AssertionError("Expected bundled runtime lookup to fail when runtime is absent")
