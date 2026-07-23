from __future__ import annotations

from datetime import datetime
from pathlib import Path
from re import sub
from uuid import uuid4

from apdu_parser.core.models import ImportedLogItem
from apdu_parser.services.path_service import PathService


class OutputService:
    def __init__(self, path_service: PathService) -> None:
        self.path_service = path_service
        self.path_service.ensure_layout()

    def allocate_result_paths(self, item: ImportedLogItem) -> tuple[Path, Path]:
        safe_name = sub(r"[^A-Za-z0-9._\-\u0080-\uFFFF]+", "_", item.source_path.stem).strip("_") or "log"
        stamp = datetime.utcnow().strftime("%Y%m%d-%H%M%S")
        run_id = uuid4().hex[:8]
        base_dir = self.path_service.output_root / f"{stamp}-{safe_name}-{run_id}"
        json_path = base_dir / "result.json"
        artifacts_dir = base_dir / "artifacts"
        return json_path, artifacts_dir
