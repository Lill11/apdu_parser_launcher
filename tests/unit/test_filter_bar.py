from __future__ import annotations

from apdu_parser.core.models import FilterMode
from apdu_parser.ui.widgets.filter_bar import FilterBar


def test_filter_bar_uses_exclusive_checked_state(qapp):
    bar = FilterBar()

    assert bar.buttons[FilterMode.ALL].isChecked()
    assert not bar.buttons[FilterMode.ES10].isChecked()

    bar.buttons[FilterMode.ES10].click()

    assert not bar.buttons[FilterMode.ALL].isChecked()
    assert bar.buttons[FilterMode.ES10].isChecked()
    assert not bar.buttons[FilterMode.FETCH_TR].isChecked()
    assert not bar.buttons[FilterMode.LSI].isChecked()

    bar.set_mode(FilterMode.LSI)

    assert not bar.buttons[FilterMode.ALL].isChecked()
    assert not bar.buttons[FilterMode.ES10].isChecked()
    assert not bar.buttons[FilterMode.FETCH_TR].isChecked()
    assert bar.buttons[FilterMode.LSI].isChecked()
