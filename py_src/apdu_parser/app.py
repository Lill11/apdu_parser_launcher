from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from PySide6.QtCore import QTimer
from PySide6.QtGui import QIcon
from PySide6.QtWidgets import QApplication

from apdu_parser import __version__
from apdu_parser.core.models import FilterMode
from apdu_parser.services.config_service import ConfigService
from apdu_parser.services.java_parser_service import JavaParserService
from apdu_parser.services.logging_service import LoggingService
from apdu_parser.services.output_service import OutputService
from apdu_parser.services.parser_management_service import ParserManagementService
from apdu_parser.services.path_service import PathService
from apdu_parser.ui.main_window import MainWindow


def _build_services() -> tuple[PathService, ConfigService, OutputService, LoggingService, JavaParserService, ParserManagementService]:
    path_service = PathService()
    config_service = ConfigService(path_service)
    output_service = OutputService(path_service)
    logging_service = LoggingService(path_service)
    java_service = JavaParserService(path_service=path_service, logging_service=logging_service)
    parser_management_service = ParserManagementService(path_service=path_service, logging_service=logging_service)
    return path_service, config_service, output_service, logging_service, java_service, parser_management_service


def _apply_window_icon(app: QApplication, path_service: PathService) -> None:
    icon_path = path_service.resource_root / "icons" / "apdu-parser.png"
    if icon_path.exists():
        app.setWindowIcon(QIcon(str(icon_path)))


def _parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--smoke-test", action="store_true")
    parser.add_argument("--smoke-ui", action="store_true")
    parser.add_argument("--smoke-input")
    parser.add_argument("--smoke-request-file")
    parser.add_argument("--smoke-report")
    return parser.parse_args(argv[1:])


def _write_smoke_report(report_path: str | None, payload: dict) -> None:
    if not report_path:
        return
    report = Path(report_path)
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")


def _assert(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def _run_smoke_test(args: argparse.Namespace) -> int:
    report: dict[str, object] = {"appVersion": __version__}
    try:
        path_service, config_service, output_service, logging_service, java_service, parser_management_service = _build_services()
        report["applicationRoot"] = str(path_service.project_root)
        report["resourceRoot"] = str(path_service.resource_root)
        report["dataRoot"] = str(path_service.app_data_root)
        parser_paths = path_service.resolve_parser_paths()
        report["javaPath"] = str(parser_paths.java_executable)
        report["javacPath"] = str(parser_paths.javac_executable)
        report["jarToolPath"] = str(parser_paths.jar_executable)
        report["parserJar"] = str(parser_paths.parser_jar)

        settings = config_service.load()
        settings.last_import_directory = str(path_service.output_root)
        config_service.save(settings)
        reloaded = config_service.load()
        report["settingsPersisted"] = reloaded.last_import_directory == str(path_service.output_root)

        smoke_input = args.smoke_input
        if args.smoke_request_file:
            payload = json.loads(Path(args.smoke_request_file).read_text(encoding="utf-8-sig"))
            smoke_input = str(payload.get("input", "")).strip()

        if smoke_input:
            source = Path(smoke_input).resolve()
            smoke_root = path_service.temp_root / "packaging-smoke"
            json_out = smoke_root / "result.json"
            artifacts = smoke_root / "artifacts"
            result = java_service.run_parser(
                input_path=source,
                json_output_path=json_out,
                artifacts_dir=artifacts,
                timeout_seconds=30,
            )
            report["sourceFile"] = str(source)
            report["resultJson"] = str(json_out)
            report["status"] = result.status
            report["success"] = result.success
            report["detectedParser"] = result.detected_parser.display_name
            report["apduCount"] = result.summary.apdu_count

        examples_root = path_service.project_root / "examples"
        sample_plugin_jar = examples_root / "sample-parser-plugin" / "build" / "sample-parser-plugin.jar"
        sample_plugin_log = path_service.temp_root / "packaging-smoke" / "sample-plugin.log"
        sample_plugin_log.parent.mkdir(parents=True, exist_ok=True)
        sample_plugin_log.write_text("SAMPLE_PLUGIN_PCSC\n--> [PCSC] 00A4040000\n", encoding="utf-8")
        source_plugin_file = examples_root / "sample-source-parser" / "SourcePcscPlugin.java"
        source_plugin_log = path_service.temp_root / "packaging-smoke" / "source-plugin.log"
        source_plugin_log.write_text("SOURCE_PLUGIN_PCSC\n--> [PCSC] 00C000000A\n", encoding="utf-8")

        parser_list_before = parser_management_service.list_parsers()
        _assert(parser_list_before.success, f"Initial parser listing failed: {parser_list_before.message}")
        report["builtInParserCount"] = sum(1 for parser in parser_list_before.parsers if parser.built_in)

        if sample_plugin_jar.exists():
            validation = parser_management_service.validate_plugin(sample_plugin_jar)
            _assert(validation.success, f"Sample parser plugin validation failed: {validation.message}")
            install_plugin = parser_management_service.install_plugin(sample_plugin_jar)
            _assert(install_plugin.success, f"Sample parser plugin install failed: {install_plugin.message}")
            _assert(install_plugin.parser is not None, "Installed sample parser plugin did not return parser metadata.")
            _assert(
                install_plugin.parser.install_directory.startswith(str(path_service.plugins_installed_root)),
                "Installed sample parser plugin was not stored under the user plugin directory.",
            )
            plugin_test = parser_management_service.test_parser(install_plugin.parser.parser_id, sample_plugin_log)
            _assert(plugin_test.success, f"Sample parser plugin test failed: {plugin_test.message}")
            _assert(plugin_test.apdu_count == 1, f"Expected 1 APDU from sample parser plugin, got {plugin_test.apdu_count}.")
            report["samplePlugin"] = {
                "parserId": install_plugin.parser.parser_id,
                "pluginJar": install_plugin.parser.plugin_jar,
                "installDirectory": install_plugin.parser.install_directory,
                "testStatus": plugin_test.status,
                "apduCount": plugin_test.apdu_count,
            }

        if source_plugin_file.exists():
            source_install = parser_management_service.install_source(source_plugin_file)
            _assert(source_install.success, f"Source parser install failed: {source_install.message}")
            _assert(source_install.parser is not None, "Installed source parser did not return parser metadata.")
            _assert(source_install.parser.source_type == "JAVA_SOURCE", "Source parser was not marked as JAVA_SOURCE.")
            _assert(
                source_install.compiler.get("path") == str(parser_paths.javac_executable),
                "Source parser install did not use the bundled javac executable.",
            )
            source_test = parser_management_service.test_parser(source_install.parser.parser_id, source_plugin_log)
            _assert(source_test.success, f"Source parser test failed: {source_test.message}")
            _assert(source_test.apdu_count == 1, f"Expected 1 APDU from source parser, got {source_test.apdu_count}.")
            source_recompile = parser_management_service.recompile_parser(source_install.parser.parser_id)
            _assert(source_recompile.success, f"Source parser recompile failed: {source_recompile.message}")
            _assert(
                source_recompile.compiler.get("path") == str(parser_paths.javac_executable),
                "Source parser recompile did not use the bundled javac executable.",
            )
            report["sourcePlugin"] = {
                "parserId": source_install.parser.parser_id,
                "pluginJar": source_install.parser.plugin_jar,
                "installDirectory": source_install.parser.install_directory,
                "preservedSourceFile": source_install.parser.preserved_source_file,
                "compilerPath": source_install.compiler.get("path", ""),
                "testStatus": source_test.status,
                "apduCount": source_test.apdu_count,
                "recompileStatus": source_recompile.status,
            }

        parser_list_after = parser_management_service.list_parsers()
        _assert(parser_list_after.success, f"Final parser listing failed: {parser_list_after.message}")
        report["managedParsers"] = [
            {
                "id": parser.parser_id,
                "name": parser.name,
                "sourceType": parser.source_type,
                "enabled": parser.enabled,
                "builtIn": parser.built_in,
            }
            for parser in parser_list_after.parsers
        ]

        if args.smoke_ui:
            app = QApplication([sys.argv[0]])
            app.setApplicationName("APDU Parser")
            app.setOrganizationName("Lill11")
            _apply_window_icon(app, path_service)
            window = MainWindow(
                config_service=config_service,
                path_service=path_service,
                output_service=output_service,
                logging_service=logging_service,
                java_service=java_service,
                parser_management_service=parser_management_service,
            )
            window.filter_bar.set_mode(FilterMode.ES10)
            es10_button = window.filter_bar.buttons[FilterMode.ES10]
            all_button = window.filter_bar.buttons[FilterMode.ALL]
            _assert(es10_button.isChecked(), "ES10 filter button did not enter the checked state.")
            _assert(not all_button.isChecked(), "ALL filter button stayed checked after selecting ES10.")
            window.filter_bar.set_mode(FilterMode.ALL)
            _assert(all_button.isChecked(), "ALL filter button did not return to the checked state.")
            window.show()
            QTimer.singleShot(0, app.quit)
            report["uiExitCode"] = app.exec()
            report["uiInitialized"] = True
            report["filterExclusive"] = True

        report["ok"] = True
        _write_smoke_report(args.smoke_report, report)
        return 0
    except Exception as exc:  # pragma: no cover - exercised in packaged smoke tests
        report["ok"] = False
        report["error"] = str(exc)
        _write_smoke_report(args.smoke_report, report)
        return 1


def main() -> int:
    args = _parse_args(sys.argv)
    if args.smoke_test:
        return _run_smoke_test(args)

    app = QApplication(sys.argv)
    app.setApplicationName("APDU Parser")
    app.setOrganizationName("Lill11")
    path_service, config_service, output_service, logging_service, java_service, parser_management_service = _build_services()
    _apply_window_icon(app, path_service)

    window = MainWindow(
        config_service=config_service,
        path_service=path_service,
        output_service=output_service,
        logging_service=logging_service,
        java_service=java_service,
        parser_management_service=parser_management_service,
    )
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
