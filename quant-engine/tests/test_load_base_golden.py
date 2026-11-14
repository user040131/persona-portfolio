"""실제 제공 파일로 만든 적재 행이 설계서의 확인값과 맞는지 (01 §2, FR-ETL-02~08, 03 §8.1 T-BOND-03).

data/raw에 원본이 없으면 건너뛴다. 읽기·변환에 1분 정도 걸린다.
"""
from __future__ import annotations

from datetime import date

import pandas as pd
import pytest



@pytest.fixture(scope="module")
def frames(real_frames):
    return real_frames


def test_날짜_복원_2010_01_04부터_2026_08_19까지_불일치_0(frames) -> None:
    r = frames.summary["restoration"]
    assert (r["first"], r["last"], r["rows"]) == ("2010-01-04", "2026-08-19", 4095)
    assert r["overlap_days"] == 1875
    assert r["mismatches"] == 0


def test_종목_808개_미분류_3_관리종목_35(frames) -> None:
    st = frames.tables["stock"].set_index("code")
    assert len(st) == 808
    assert (st["sector"] == "미분류").sum() == 3
    assert st["is_managed"].sum() == 35
    assert st.at["005930", "name"] == "삼성전자"
    assert st.at["000660", "name"] == "SK하이닉스"


def test_삼성전자_종가와_KOSPI(frames) -> None:
    p = frames.tables["price_daily"]
    last = p[(p["code"] == "005930") & (p["date"] == date(2026, 8, 19))]["close"]
    assert last.tolist() == [247500.0]
    k = frames.tables["index_daily"]
    assert k[k["date"] == date(2026, 8, 19)]["close"].tolist() == [6471.17]


def test_재무_적용일과_출처(frames) -> None:
    f = frames.tables["financial_annual"]
    row = f[(f["code"] == "005930") & (f["fiscal_year"] == 2025)].iloc[0]
    assert row["apply_date"] == date(2026, 4, 1)
    assert (row["eps"], row["bps"]) == (6564, 63997)
    assert set(f["source"]) == {"FILE"}


def test_채권_지수_첫날_100_최근_7년_최대_하락_약_6퍼센트(frames) -> None:
    b = frames.tables["bond_index_daily"]
    idx = pd.Series(b["idx"].to_numpy(), index=pd.to_datetime(b["date"]))
    assert idx.iloc[0] == 100.0
    w = idx.loc["2019-08-19":]
    assert float((w / w.cummax() - 1).min()) == pytest.approx(-0.063, abs=0.005)
