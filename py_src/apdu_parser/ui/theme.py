from __future__ import annotations

from apdu_parser.services.path_service import PathService


def load_stylesheet() -> str:
    qss_path = PathService().resource_root / "styles" / "app.qss"
    return qss_path.read_text(encoding="utf-8")
