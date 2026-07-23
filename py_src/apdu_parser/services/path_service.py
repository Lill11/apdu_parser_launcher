from __future__ import annotations

import os
import sys
from pathlib import Path

from apdu_parser.core.models import ParserPaths


class PathService:
    def __init__(self, project_root: Path | None = None) -> None:
        self.project_root = project_root or self._detect_project_root()

    @staticmethod
    def is_frozen() -> bool:
        return bool(getattr(sys, "frozen", False))

    @classmethod
    def _detect_project_root(cls) -> Path:
        if cls.is_frozen():
            return Path(sys.executable).resolve().parent
        return Path(__file__).resolve().parents[3]

    @property
    def resource_root(self) -> Path:
        direct = self.project_root / "resources"
        if direct.exists():
            return direct
        bundled = self.project_root / "_internal" / "resources"
        if bundled.exists():
            return bundled
        source_tree = self.project_root / "py_src" / "apdu_parser" / "resources"
        if source_tree.exists():
            return source_tree
        return direct

    @property
    def app_data_root(self) -> Path:
        override = os.environ.get("APDU_PARSER_DATA_ROOT")
        if override:
            return Path(override)
        local = os.environ.get("LOCALAPPDATA")
        if local:
            return Path(local) / "APDUParser"
        return Path.home() / "AppData" / "Local" / "APDUParser"

    @property
    def config_dir(self) -> Path:
        return self.app_data_root / "config"

    @property
    def output_root(self) -> Path:
        return self.app_data_root / "output"

    @property
    def temp_root(self) -> Path:
        return self.app_data_root / "temp"

    @property
    def logs_root(self) -> Path:
        return self.app_data_root / "logs"

    @property
    def diagnostics_root(self) -> Path:
        return self.app_data_root / "diagnostics"

    @property
    def plugins_root(self) -> Path:
        return self.app_data_root / "plugins"

    @property
    def plugins_installed_root(self) -> Path:
        return self.plugins_root / "installed"

    @property
    def settings_path(self) -> Path:
        return self.config_dir / "ui-settings.json"

    @property
    def parser_jar_path(self) -> Path:
        return self.project_root / "parser" / "apdu-parser.jar"

    def _runtime_tool_path(self, tool_name: str) -> Path:
        return self.project_root / "runtime" / "bin" / tool_name

    def runtime_java_path(self) -> Path:
        return self._runtime_tool_path("java.exe")

    def runtime_javac_path(self) -> Path:
        return self._runtime_tool_path("javac.exe")

    def runtime_jar_path(self) -> Path:
        return self._runtime_tool_path("jar.exe")

    def ensure_layout(self) -> None:
        for path in (
            self.config_dir,
            self.output_root,
            self.temp_root,
            self.logs_root,
            self.diagnostics_root,
            self.plugins_installed_root,
        ):
            path.mkdir(parents=True, exist_ok=True)

    def resolve_parser_paths(self) -> ParserPaths:
        jar_path = self.parser_jar_path
        if not jar_path.exists():
            raise FileNotFoundError(f"Parser JAR is missing: {jar_path}")

        runtime_java = self.runtime_java_path()
        runtime_javac = self.runtime_javac_path()
        runtime_jar = self.runtime_jar_path()
        if runtime_java.exists() and runtime_javac.exists() and runtime_jar.exists():
            return ParserPaths(
                java_executable=runtime_java,
                javac_executable=runtime_javac,
                jar_executable=runtime_jar,
                parser_jar=jar_path,
            )

        if self.is_frozen():
            raise FileNotFoundError(
                "Bundled Java toolchain is incomplete. Expected runtime/bin/java.exe, runtime/bin/javac.exe, and runtime/bin/jar.exe."
            )

        java_home = os.environ.get("JAVA_HOME")
        if java_home:
            home = Path(java_home)
            candidate_java = home / "bin" / "java.exe"
            candidate_javac = home / "bin" / "javac.exe"
            candidate_jar = home / "bin" / "jar.exe"
            if candidate_java.exists() and candidate_javac.exists() and candidate_jar.exists():
                return ParserPaths(
                    java_executable=candidate_java,
                    javac_executable=candidate_javac,
                    jar_executable=candidate_jar,
                    parser_jar=jar_path,
                )

        known = [
            Path(r"C:\Program Files\BellSoft\LibericaJDK-17-Full"),
            Path(r"C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"),
        ]
        for home in known:
            candidate_java = home / "bin" / "java.exe"
            candidate_javac = home / "bin" / "javac.exe"
            candidate_jar = home / "bin" / "jar.exe"
            if candidate_java.exists() and candidate_javac.exists() and candidate_jar.exists():
                return ParserPaths(
                    java_executable=candidate_java,
                    javac_executable=candidate_javac,
                    jar_executable=candidate_jar,
                    parser_jar=jar_path,
                )

        raise FileNotFoundError("No bundled or local Java toolchain was found.")
