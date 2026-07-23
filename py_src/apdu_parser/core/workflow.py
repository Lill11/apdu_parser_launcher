from __future__ import annotations

from pathlib import Path
from typing import Iterable
from uuid import uuid4

from apdu_parser.core.models import ImportedLogItem, LogStatus, ParseResult


class WorkflowStore:
    def __init__(self) -> None:
        self._items: list[ImportedLogItem] = []

    @property
    def items(self) -> list[ImportedLogItem]:
        return list(self._items)

    def add_files(self, paths: Iterable[Path]) -> list[ImportedLogItem]:
        created: list[ImportedLogItem] = []
        seen = {item.source_path.resolve() for item in self._items}
        for path in paths:
            resolved = path.resolve()
            if resolved in seen:
                continue
            item = ImportedLogItem(item_id=uuid4().hex, source_path=resolved)
            self._items.append(item)
            created.append(item)
            seen.add(resolved)
        return created

    def remove_item(self, item_id: str) -> None:
        self._items = [item for item in self._items if item.item_id != item_id]

    def clear(self) -> None:
        self._items.clear()

    def get(self, item_id: str) -> ImportedLogItem | None:
        for item in self._items:
            if item.item_id == item_id:
                return item
        return None

    def update_result(self, item_id: str, result: ParseResult) -> ImportedLogItem | None:
        item = self.get(item_id)
        if not item:
            return None
        item.result = result
        item.detected_format = result.detected_parser.display_name or "Unsupported"
        item.result_summary = result.message
        item.error_message = result.errors[0].details if result.errors else ""
        if result.summary.exit_code == 0:
            item.status = LogStatus.COMPLETED
        elif result.summary.exit_code == 1:
            item.status = LogStatus.UNSUPPORTED
        else:
            item.status = LogStatus.FAILED
        return item
