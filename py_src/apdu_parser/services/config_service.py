from __future__ import annotations

import json
from dataclasses import asdict, dataclass

from apdu_parser.services.path_service import PathService


@dataclass(slots=True)
class UiSettings:
    window_width: int = 1360
    window_height: int = 860
    window_x: int | None = None
    window_y: int | None = None
    splitter_sizes: list[int] | None = None
    last_import_directory: str = ""
    last_output_directory: str = ""
    parser_timeout_seconds: int = 45
    retain_temporary_files: bool = False
    max_parallel_jobs: int = 2


class ConfigService:
    def __init__(self, path_service: PathService) -> None:
        self.path_service = path_service
        self.path_service.ensure_layout()

    def load(self) -> UiSettings:
        path = self.path_service.settings_path
        if not path.exists():
            return UiSettings()
        payload = json.loads(path.read_text(encoding="utf-8"))
        return UiSettings(
            window_width=int(payload.get("window_width", 1360)),
            window_height=int(payload.get("window_height", 860)),
            window_x=payload.get("window_x"),
            window_y=payload.get("window_y"),
            splitter_sizes=payload.get("splitter_sizes"),
            last_import_directory=str(payload.get("last_import_directory", "")),
            last_output_directory=str(payload.get("last_output_directory", "")),
            parser_timeout_seconds=int(payload.get("parser_timeout_seconds", 45)),
            retain_temporary_files=bool(payload.get("retain_temporary_files", False)),
            max_parallel_jobs=max(1, int(payload.get("max_parallel_jobs", 2))),
        )

    def save(self, settings: UiSettings) -> None:
        self.path_service.config_dir.mkdir(parents=True, exist_ok=True)
        self.path_service.settings_path.write_text(
            json.dumps(asdict(settings), indent=2, ensure_ascii=False),
            encoding="utf-8",
        )
