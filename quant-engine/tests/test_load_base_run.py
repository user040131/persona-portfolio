"""load_base 실행 흐름: etl_log 상태, 멱등, 실패 시 롤백 (01 FR-ETL-01·09). 원본 대신 작은 합성 프레임을 쓴다."""
from __future__ import annotations

from datetime import date

import pandas as pd
import pytest
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.config import Settings
from app.data.status import read_status
from app.db.engine import make_engine
from app.db.models import EtlLog, PriceDaily, Stock
from app.etl import load_base
from app.etl.load_base import Frames
from tests.factories import tiny_tables


def tiny_frames(close: float = 247500.0) -> Frames:
    return Frames(tiny_tables(close), {"note": "tiny"})


@pytest.fixture
def engine(settings: Settings):
    e = make_engine(settings.database_url)
    yield e
    e.dispose()


def _count(engine, model) -> int:
    with Session(engine) as db:
        return db.scalar(select(func.count()).select_from(model))


def test_성공하면_SUCCESS와_새_data_version(monkeypatch, settings, engine) -> None:
    monkeypatch.setattr(load_base, "build_frames", lambda *a, **k: tiny_frames())
    etl_id = load_base.run(settings, engine)

    with Session(engine) as db:
        row = db.get(EtlLog, etl_id)
        assert row.status == "SUCCESS" and row.job == "load_base"
        assert row.steps["summary"] == {"note": "tiny"}
        assert {s["table"] for s in row.steps["steps"]} >= {"price_daily", "stock"}
        st = read_status(db)
    assert st.data_loaded and st.data_version == etl_id and st.as_of == date(2026, 8, 19)
    assert _count(engine, PriceDaily) == 2


def test_두_번_돌려도_행_수가_같고_버전만_오른다(monkeypatch, settings, engine) -> None:
    monkeypatch.setattr(load_base, "build_frames", lambda *a, **k: tiny_frames())
    first = load_base.run(settings, engine)
    second = load_base.run(settings, engine)
    assert second > first
    assert _count(engine, PriceDaily) == 2 and _count(engine, Stock) == 1


def test_읽기_단계에서_실패하면_FAILED로_남긴다(monkeypatch, settings, engine) -> None:
    def boom(*a, **k):
        raise load_base.validators.EtlValidationError("날짜 복원 검증 실패: 테스트")

    monkeypatch.setattr(load_base, "build_frames", boom)
    with pytest.raises(load_base.validators.EtlValidationError):
        load_base.run(settings, engine)
    with Session(engine) as db:
        row = db.scalars(select(EtlLog).order_by(EtlLog.id.desc())).first()
    assert row.status == "FAILED" and "날짜 복원" in row.message
    with Session(engine) as db:
        assert not read_status(db).data_loaded


def test_쓰는_중에_실패하면_이전_데이터가_그대로다(monkeypatch, settings, engine) -> None:
    monkeypatch.setattr(load_base, "build_frames", lambda *a, **k: tiny_frames())
    load_base.run(settings, engine)

    broken = tiny_frames()
    broken.tables["price_daily"] = pd.DataFrame({"code": ["005930"], "date": [date(2026, 8, 19)], "close": [None]})
    monkeypatch.setattr(load_base, "build_frames", lambda *a, **k: broken)
    with pytest.raises(Exception):
        load_base.run(settings, engine)

    assert _count(engine, PriceDaily) == 2         # 한 트랜잭션이라 지운 것까지 되돌려짐
    with Session(engine) as db:
        assert read_status(db).data_loaded          # 이전 성공은 유효
