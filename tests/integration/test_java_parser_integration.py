from __future__ import annotations

from pathlib import Path
import subprocess

from apdu_parser.services.java_parser_service import JavaParserService
from apdu_parser.services.logging_service import LoggingService
from apdu_parser.services.path_service import PathService


PROJECT_ROOT = Path(__file__).resolve().parents[2]


def make_service(tmp_path, monkeypatch) -> JavaParserService:
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path / "LocalAppData"))
    monkeypatch.setenv("APDU_PARSER_DATA_ROOT", str(tmp_path / "AppDataRoot"))
    subprocess.run(["cmd", "/c", str(PROJECT_ROOT / "build-parser.bat")], cwd=PROJECT_ROOT, check=True)
    path_service = PathService(project_root=PROJECT_ROOT)
    return JavaParserService(path_service=path_service, logging_service=LoggingService(path_service))


def test_subprocess_handles_unicode_paths(tmp_path, monkeypatch):
    service = make_service(tmp_path, monkeypatch)
    source_dir = tmp_path / "Pruebas" / "客户 logs (01)"
    source_dir.mkdir(parents=True)
    source = source_dir / "análisis sesión.测试.log"
    source.write_text("--> [PCSC] 00A4040000\n", encoding="utf-8")
    output_dir = tmp_path / "Resultados múltiples" / "case.v1"
    json_out = output_dir / "resultado.final.json"
    artifacts = output_dir / "artifact set"
    result = service.run_parser(
        input_path=source,
        json_output_path=json_out,
        artifacts_dir=artifacts,
        timeout_seconds=10,
    )
    assert result.source_file.resolve() == source.resolve()
    assert result.raw["sourceFile"] == str(source.resolve())
    assert result.output_files.json == json_out
    assert result.summary.apdu_count == 1


def test_multiple_files_round_trip(tmp_path, monkeypatch):
    service = make_service(tmp_path, monkeypatch)
    names = ["uno.log", "dos con espacio.log", "tres.muchos.puntos.log"]
    results = []
    for idx, name in enumerate(names, start=1):
        source = tmp_path / name
        source.write_text("--> [PCSC] 00A4040000\n", encoding="utf-8")
        base = tmp_path / f"out{idx}"
        result = service.run_parser(
            input_path=source,
            json_output_path=base / "result.json",
            artifacts_dir=base / "artifacts",
            timeout_seconds=10,
        )
        results.append(result)
    assert [result.summary.apdu_count for result in results] == [1, 1, 1]


def test_unsupported_result_maps_cleanly(tmp_path, monkeypatch):
    service = make_service(tmp_path, monkeypatch)
    source = tmp_path / "unsupported.log"
    source.write_text("hello world\n", encoding="utf-8")
    result = service.run_parser(
        input_path=source,
        json_output_path=tmp_path / "unsupported" / "result.json",
        artifacts_dir=tmp_path / "unsupported" / "artifacts",
        timeout_seconds=10,
    )
    assert not result.success
    assert result.status == "unsupported"
    assert result.summary.exit_code == 1
