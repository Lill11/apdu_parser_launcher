from __future__ import annotations

import os
import shutil
import subprocess
import zipfile
from pathlib import Path

import pytest

from apdu_parser.services.java_parser_service import JavaParserError
from apdu_parser.services.logging_service import LoggingService
from apdu_parser.services.parser_management_service import ParserManagementService
from apdu_parser.services.path_service import PathService


PROJECT_ROOT = Path(__file__).resolve().parents[2]
SAMPLE_PLUGIN_JAR = PROJECT_ROOT / "examples" / "sample-parser-plugin" / "build" / "sample-parser-plugin.jar"
SAMPLE_PLUGIN_LOG = PROJECT_ROOT / "examples" / "sample-parser-plugin" / "sample.log"
SAMPLE_SOURCE_FILE = PROJECT_ROOT / "examples" / "sample-source-parser" / "SourcePcscPlugin.java"
SAMPLE_LEGACY_SOURCE_FILE = PROJECT_ROOT / "examples" / "sample-legacy-extractor" / "LegacyPcscExtractor.java"


def build_parser_and_plugin() -> None:
    subprocess.run(["cmd", "/c", str(PROJECT_ROOT / "build-parser.bat")], cwd=PROJECT_ROOT, check=True)
    subprocess.run(["cmd", "/c", str(PROJECT_ROOT / "examples" / "sample-parser-plugin" / "build-sample-plugin.bat")], cwd=PROJECT_ROOT, check=True)


def resolve_javac() -> str:
    candidates = [
        Path(os.environ.get("APDU_PARSER_JAVAC", "")),
        Path(r"C:\Program Files\BellSoft\LibericaJDK-17-Full\bin\javac.exe"),
        Path(r"C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\javac.exe"),
    ]
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidates.insert(0, Path(java_home) / "bin" / "javac.exe")
    for candidate in candidates:
        if candidate and str(candidate) and candidate.exists():
            return str(candidate)
    pytest.skip("No configured javac.exe is available for Phase B integration tests.")


def make_service(tmp_path, monkeypatch) -> ParserManagementService:
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path / "LocalAppData"))
    monkeypatch.setenv("APDU_PARSER_DATA_ROOT", str(tmp_path / "AppDataRoot"))
    monkeypatch.setenv("APDU_PARSER_JAVAC", resolve_javac())
    path_service = PathService(project_root=PROJECT_ROOT)
    return ParserManagementService(path_service=path_service, logging_service=LoggingService(path_service))


def write_source(path: Path, content: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return path


def test_install_disable_enable_test_and_remove_plugin(tmp_path, monkeypatch):
    build_parser_and_plugin()
    service = make_service(tmp_path, monkeypatch)

    validation = service.validate_plugin(SAMPLE_PLUGIN_JAR)
    assert validation.success
    assert validation.parser is not None
    assert validation.parser.parser_id == "sample_pcsc_plugin"

    install = service.install_plugin(SAMPLE_PLUGIN_JAR)
    assert install.success
    assert install.parser is not None
    assert install.parser.install_directory

    listing = service.list_parsers()
    ids = {parser.parser_id for parser in listing.parsers}
    assert "sample_pcsc_plugin" in ids

    test_result = service.test_parser("sample_pcsc_plugin", SAMPLE_PLUGIN_LOG)
    assert test_result.success
    assert test_result.matched
    assert test_result.apdu_count == 1

    unicode_log = tmp_path / "logs con espacios" / "中文 español sample.log"
    unicode_log.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(SAMPLE_PLUGIN_LOG, unicode_log)
    unicode_test = service.test_parser("sample_pcsc_plugin", unicode_log)
    assert unicode_test.success
    assert unicode_test.matched
    assert unicode_test.apdu_count == 1

    disabled = service.disable_parser("sample_pcsc_plugin")
    assert disabled.success
    assert disabled.parser is not None
    assert not disabled.parser.enabled

    enabled = service.enable_parser("sample_pcsc_plugin")
    assert enabled.success
    assert enabled.parser is not None
    assert enabled.parser.enabled

    removed = service.remove_plugin("sample_pcsc_plugin")
    assert removed.success
    listing_after = service.list_parsers()
    ids_after = {parser.parser_id for parser in listing_after.parsers}
    assert "sample_pcsc_plugin" not in ids_after


def test_install_java_source_and_recompile(tmp_path, monkeypatch):
    build_parser_and_plugin()
    service = make_service(tmp_path, monkeypatch)

    source_dir = tmp_path / "源码 con espacios"
    source_file = source_dir / "SourcePcscPlugin.java"
    source_dir.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(SAMPLE_SOURCE_FILE, source_file)

    install = service.install_source(source_file)
    assert install.success
    assert install.parser is not None
    assert install.parser.source_type == "JAVA_SOURCE"
    assert install.parser.preserved_source_file.endswith("SourcePcscPlugin.java")
    assert install.parser.compile_log_path.endswith("compile.log")
    assert install.parser.last_compilation_status == "SUCCESS"

    parser = install.parser
    jar_path = Path(parser.plugin_jar)
    assert jar_path.exists()
    with zipfile.ZipFile(jar_path) as jar:
        services = jar.read("META-INF/services/apdu.parser.plugin.api.ApduParserPlugin").decode("utf-8").strip()
        assert services == "example.source.SourcePcscPlugin"

    listing = service.list_parsers()
    listed = next(item for item in listing.parsers if item.parser_id == "source_pcsc_plugin")
    assert listed.original_source_path.endswith("SourcePcscPlugin.java")
    assert listed.last_compilation_status == "SUCCESS"

    source_test_log = tmp_path / "输入 logs" / "source plugin sample.log"
    source_test_log.parent.mkdir(parents=True, exist_ok=True)
    source_test_log.write_text("SOURCE_PLUGIN_PCSC\n--> [PCSC] 00A4040000\n", encoding="utf-8")
    test_result = service.test_parser("source_pcsc_plugin", source_test_log)
    assert test_result.success
    assert test_result.matched
    assert test_result.apdu_count == 1

    preserved_source = Path(listed.preserved_source_file)
    original_text = preserved_source.read_text(encoding="utf-8")
    preserved_source.write_text(original_text.replace("return \"1.0.0\";", "return \"1.1.0\";"), encoding="utf-8")
    recompiled = service.recompile_parser("source_pcsc_plugin")
    assert recompiled.success
    assert recompiled.parser is not None
    assert recompiled.parser.version == "1.1.0"


def test_recompile_failure_preserves_previous_working_plugin(tmp_path, monkeypatch):
    build_parser_and_plugin()
    service = make_service(tmp_path, monkeypatch)

    source_file = tmp_path / "source ok" / "SourcePcscPlugin.java"
    write_source(source_file, SAMPLE_SOURCE_FILE.read_text(encoding="utf-8"))
    install = service.install_source(source_file)
    assert install.success
    assert install.parser is not None

    parser_before = install.parser
    preserved = Path(parser_before.preserved_source_file)
    preserved.write_text(SAMPLE_SOURCE_FILE.read_text(encoding="utf-8").replace("return \"1.0.0\";", "return ;"), encoding="utf-8")

    recompilation = service.recompile_parser("source_pcsc_plugin")
    assert not recompilation.success
    assert recompilation.compile_log
    assert "failed" in recompilation.status.lower() or recompilation.status == "COMPILATION_FAILED"

    test_log = tmp_path / "source plugin sample.log"
    test_log.write_text("SOURCE_PLUGIN_PCSC\n--> [PCSC] 00A4040000\n", encoding="utf-8")
    still_works = service.test_parser("source_pcsc_plugin", test_log)
    assert still_works.success
    assert still_works.matched
    assert still_works.apdu_count == 1

    listing = service.list_parsers()
    listed = next(item for item in listing.parsers if item.parser_id == "source_pcsc_plugin")
    assert listed.version == "1.0.0"
    assert listed.last_compilation_status == "FAILED"
    assert Path(listed.compile_log_path).exists()


def test_invalid_source_cases(tmp_path, monkeypatch):
    build_parser_and_plugin()
    service = make_service(tmp_path, monkeypatch)

    missing_interface = write_source(
        tmp_path / "bad" / "MissingInterfaceParser.java",
        "public class MissingInterfaceParser { }\n",
    )
    result = service.install_source(missing_interface)
    assert not result.success
    assert result.status == "INVALID_SOURCE"

    mismatch = write_source(
        tmp_path / "bad2" / "WrongName.java",
        "public class ActualName implements apdu.parser.plugin.api.ApduParserPlugin {"
        "public String getId(){return \"x\";} public String getName(){return \"x\";} public String getVersion(){return \"1\";}"
        "public int getPluginApiVersion(){return apdu.parser.plugin.api.PluginConstants.CURRENT_PLUGIN_API_VERSION;}"
        "public java.util.List<String> getSupportedExtensions(){return java.util.List.of(\".log\");}"
        "public apdu.parser.plugin.api.PluginDetectionResult detect(java.nio.file.Path p, byte[] s){return apdu.parser.plugin.api.PluginDetectionResult.noMatch(\"x\");}"
        "public apdu.parser.plugin.api.PluginParseResult parse(java.nio.file.Path p){return new apdu.parser.plugin.api.PluginParseResult(java.util.List.of(), java.util.List.of());}}",
    )
    mismatch_result = service.install_source(mismatch)
    assert not mismatch_result.success
    assert mismatch_result.status == "INVALID_SOURCE"

    invalid_api = write_source(
        tmp_path / "bad3" / "InvalidApiParser.java",
        SAMPLE_SOURCE_FILE.read_text(encoding="utf-8")
        .replace("class SourcePcscPlugin", "class InvalidApiParser")
        .replace("source_pcsc_plugin", "invalid_api_plugin")
        .replace("Source PCSC Plugin", "Invalid API Plugin")
        .replace("return PluginConstants.CURRENT_PLUGIN_API_VERSION;", "return PluginConstants.CURRENT_PLUGIN_API_VERSION + 1;"),
    )
    invalid_api_result = service.install_source(invalid_api)
    assert not invalid_api_result.success
    assert invalid_api_result.status == "INCOMPATIBLE_PLUGIN_API"


def test_duplicate_parser_id_and_missing_dependency(tmp_path, monkeypatch):
    build_parser_and_plugin()
    service = make_service(tmp_path, monkeypatch)

    first = service.install_source(write_source(tmp_path / "dup" / "SourcePcscPlugin.java", SAMPLE_SOURCE_FILE.read_text(encoding="utf-8")))
    assert first.success

    duplicate = service.install_source(write_source(tmp_path / "dup2" / "SourcePcscPlugin.java", SAMPLE_SOURCE_FILE.read_text(encoding="utf-8")))
    assert not duplicate.success
    assert duplicate.status == "DUPLICATE_PARSER_ID"

    missing_dep = write_source(
        tmp_path / "dep" / "MissingDependencyParser.java",
        SAMPLE_SOURCE_FILE.read_text(encoding="utf-8")
        .replace("class SourcePcscPlugin", "class MissingDependencyParser")
        .replace("source_pcsc_plugin", "missing_dependency_plugin")
        .replace("Source PCSC Plugin", "Missing Dependency Plugin")
        .replace("import java.util.regex.Pattern;", "import com.example.DoesNotExist;\nimport java.util.regex.Pattern;"),
    )
    missing_dep_result = service.install_source(missing_dep)
    assert not missing_dep_result.success
    assert missing_dep_result.status == "COMPILATION_FAILED"


def test_built_in_parser_cannot_be_removed(tmp_path, monkeypatch):
    build_parser_and_plugin()
    service = make_service(tmp_path, monkeypatch)

    with pytest.raises(JavaParserError):
        service.remove_plugin("pcsc_terminal")


def test_plugin_enabled_state_persists_across_service_instances(tmp_path, monkeypatch):
    build_parser_and_plugin()
    service = make_service(tmp_path, monkeypatch)

    install = service.install_plugin(SAMPLE_PLUGIN_JAR)
    assert install.success

    disabled = service.disable_parser("sample_pcsc_plugin")
    assert disabled.success
    assert disabled.parser is not None
    assert not disabled.parser.enabled

    fresh_service = make_service(tmp_path, monkeypatch)
    listing = fresh_service.list_parsers()
    parser = next(parser for parser in listing.parsers if parser.parser_id == "sample_pcsc_plugin")
    assert not parser.enabled
    assert parser.validation_status == "DISABLED"


def test_install_legacy_source_and_recompile(tmp_path, monkeypatch):
    build_parser_and_plugin()
    service = make_service(tmp_path, monkeypatch)

    source_dir = tmp_path / "legacy 源码 con espacios"
    source_file = source_dir / "LegacyPcscExtractor.java"
    source_dir.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(SAMPLE_LEGACY_SOURCE_FILE, source_file)

    sample_log = tmp_path / "legacy logs" / "muestra 中文 sample.txt"
    sample_log.parent.mkdir(parents=True, exist_ok=True)
    sample_log.write_text("--> [LEGACY] 00 A4 04 00 00\n--> [LEGACY] 80 12 00 00 0B\n", encoding="utf-8")

    inspection = service.inspect_legacy_source(source_file)
    assert inspection.success
    assert inspection.public_class_name == "LegacyPcscExtractor"
    assert inspection.main_class_name == "LegacyPcscExtractor"

    install = service.install_legacy_source(
        source_path=source_file,
        parser_name="Legacy PCSC Extractor",
        parser_id="legacy_pcsc_extractor",
        version="1.0.0",
        supported_extensions=[".txt", ".log"],
        command_pattern="INPUT_FILE_OUTPUT_FILE",
        output_file_name="apdus.txt",
        sample_input=sample_log,
    )
    assert install.success
    assert install.parser is not None
    assert install.parser.source_type == "LEGACY_JAVA_EXTRACTOR"
    assert install.parser.legacy_main_class == "LegacyPcscExtractor"
    assert install.apdu_count == 2
    assert "Legacy extractor wrote output" in install.stdout

    listed = next(parser for parser in service.list_parsers().parsers if parser.parser_id == "legacy_pcsc_extractor")
    assert listed.last_test_status == "SUCCESS"

    tested = service.test_parser("legacy_pcsc_extractor", sample_log)
    assert tested.success
    assert tested.apdu_count == 2
    assert tested.output_path
    assert "Legacy extractor wrote output" in tested.stdout

    preserved_source = Path(listed.preserved_source_file)
    original_text = preserved_source.read_text(encoding="utf-8")
    preserved_source.write_text(original_text.replace("Legacy PCSC", "Legacy PCSC"), encoding="utf-8")
    recompiled = service.recompile_parser("legacy_pcsc_extractor")
    assert recompiled.success

    removed = service.remove_plugin("legacy_pcsc_extractor")
    assert removed.success
    ids_after = {parser.parser_id for parser in service.list_parsers().parsers}
    assert "legacy_pcsc_extractor" not in ids_after
