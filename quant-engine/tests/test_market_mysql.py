"""MySQL에서 시장 데이터를 읽어 캐시로 만들고, 피클 캐시를 다시 쓰는지 (실제 MySQL 8.4)."""
from __future__ import annotations

import pandas as pd
import pytest

from app.config import Settings
from app.data.market_data import MarketData
from app.db.engine import init_schema, make_engine
from app.db.models import Base
from app.etl import load_base
from app.etl.load_base import Frames
from tests.factories import tiny_tables

pytestmark = pytest.mark.mysql


def test_적재한_데이터를_읽고_캐시를_남긴다(mysql_url, tmp_path, monkeypatch) -> None:
    engine = make_engine(mysql_url)
    Base.metadata.drop_all(engine)                                  # 다른 테스트가 남긴 것 지우기
    init_schema(engine)
    assert MarketData.from_db(engine, tmp_path) is None             # 적재 전

    monkeypatch.setattr(load_base, "build_frames", lambda *a, **k: Frames(tiny_tables(), {}))
    version = load_base.run(Settings(database_url=mysql_url), engine)

    md = MarketData.from_db(engine, tmp_path)
    assert md.data_version == version
    assert md.as_of == pd.Timestamp("2026-08-19")
    assert md.close.loc["2026-08-19", "005930"] == 247500.0
    assert md.snapshot().fin.loc["005930", "eps"] == 6564.0
    assert (tmp_path / f"market_v{version}.pkl").exists()

    again = MarketData.from_db(engine, tmp_path)                    # 피클에서 읽음
    assert again.close.equals(md.close)
    engine.dispose()
