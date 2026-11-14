"""실제 데이터 골든 값 (docs/03 §1.5 골든, §8.2 T-IND-01·02, T-SCR-01). data/raw가 없으면 건너뛴다."""
from __future__ import annotations

import pandas as pd
import pytest

from app.domain import indicators, screening
from app.services.stock_service import latest_items
from app.config import Settings


def test_T_IND_01_삼성전자_2026_08_19(real_market) -> None:
    ind = indicators.compute(real_market.snapshot("2026-08-19"))
    row = ind.loc["005930"]
    expected = {"close": 247500, "per": 37.705667, "pbr": 3.867369, "psr": 5.002931, "pcr": 18.114616,
                "roe": 0.103609, "roa": 0.079738, "momentum_6m": 0.216216, "vol_1y": 0.731913, "rsi_14": 0.611429}
    for k, v in expected.items():
        assert row[k] == pytest.approx(v, abs=5e-7), k


def test_T_IND_02_재무는_4월_1일에_바뀌고_미래_가격은_없다(real_market) -> None:
    before, after = real_market.snapshot("2026-03-31"), real_market.snapshot("2026-04-01")
    assert before.fin.loc["005930", "fiscal_year"] == 2024
    assert after.fin.loc["005930", "fiscal_year"] == 2025
    assert before.close_hist.index.max() == pd.Timestamp("2026-03-31")


def test_T_SCR_01_상장폐지_위험_2026_08_19(real_market) -> None:
    snap = real_market.snapshot("2026-08-19")
    rules = screening.delist_rules(indicators.compute(snap), snap)
    counts = {r: int(rules.map(lambda x, r=r: r in x).sum()) for r in ["D1", "D2", "D3", "D4", "D5"]}
    # D1은 BPS가 음수인 종목을 시총 계산 불가(NaN)로 두므로 50. 그 종목은 D4(자본잠식)로 걸린다
    assert counts == {"D1": 50, "D2": 11, "D3": 31, "D4": 7, "D5": 35}
    assert int(rules.map(bool).sum()) == 91


def test_공통_필터_후보_수와_사유(real_market) -> None:
    snap = real_market.snapshot("2026-08-19")
    r = screening.screen_common(indicators.compute(snap), snap)
    assert len(r.universe) == 432
    # INSUFFICIENT_HISTORY 1 = 000300: 1년 내내 종가가 같아(거래정지) 변동성 0 → 비교할 가격 자료 없음
    assert r.reasons.value_counts().to_dict() == {"LOSS_MAKING": 146, "DELIST_RISK": 91, "NO_FINANCIAL": 88,
                                                 "SMALL_CAP": 48, "NO_PRICE": 2, "INSUFFICIENT_HISTORY": 1}
    assert "005930" in r.universe


def test_종목_목록은_808개이고_삼성전자는_위험_없음(real_market) -> None:
    items = {i.code: i for i in latest_items(real_market, Settings())}
    assert len(items) == 808
    assert items["005930"].last_close == 247500.0 and items["005930"].delist_rules == []
    assert sum(1 for i in items.values() if i.delist_rules) == 91
