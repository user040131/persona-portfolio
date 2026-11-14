"""지표·상장폐지 위험·공통 필터 단위 테스트 (합성 데이터). docs/03 §1.5·§1.6, §8.2."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from app.data.market_data import MarketData, expected_fiscal_year
from app.domain import indicators, screening
from tests.factories import random_walk, snapshot, tiny_tables

N = 300   # 모멘텀(121행)·변동성(200개 이상) 모두 계산되는 길이


def test_가치_지표와_분모가_0_이하면_NaN() -> None:
    snap = snapshot({"A": [10_000.0] * N, "B": [10_000.0] * N},
                    fin={"B": {"eps": -100.0, "bps": 0.0, "sps": 5_000.0, "ceps": -1.0}})
    ind = indicators.compute(snap)
    assert ind.at["A", "per"] == pytest.approx(10.0)          # 10,000 / 1,000
    assert ind.at["A", "pbr"] == pytest.approx(1.0)
    assert ind.at["A", "psr"] == pytest.approx(2.0)
    assert ind.at["A", "pcr"] == pytest.approx(10_000 / 1_500)
    assert np.isnan(ind.at["B", "per"]) and np.isnan(ind.at["B", "pbr"]) and np.isnan(ind.at["B", "pcr"])
    assert np.isnan(ind.at["B", "mcap"])                       # bps ≤ 0이면 주식 수를 추정할 수 없다


def test_수익성은_순이익과_영업이익이_모두_양수일_때만() -> None:
    snap = snapshot({"A": [1.0] * N, "B": [1.0] * N, "C": [1.0] * N},
                    fin={"B": {"operating_income": -1.0}, "C": {"net_income": 0.0}})
    ind = indicators.compute(snap)
    assert ind.at["A", "roe"] == pytest.approx(6e7 / 7e8)
    assert ind.at["A", "roa"] == pytest.approx(6e7 / 1e9)
    assert ind[["roe", "roa"]].loc[["B", "C"]].isna().all().all()


def test_시가총액_배당수익률_부채비율() -> None:
    snap = snapshot({"A": [20_000.0] * N}, fin={"A": {"dividends": None}})
    ind = indicators.compute(snap)
    shares = 7e8 * 1000 / 10_000               # 자본총계(천원)×1000 ÷ BPS = 7천만 주
    assert ind.at["A", "shares_est"] == pytest.approx(shares)
    assert ind.at["A", "mcap"] == pytest.approx(20_000 * shares)
    assert ind.at["A", "div_yield"] == 0.0     # 배당금 없음 → 0
    assert ind.at["A", "debt_ratio"] == pytest.approx(3e8 / 7e8)
    assert ind.at["A", "revenue_won"] == pytest.approx(5e11)


def test_배당수익률은_배당금_절댓값_나누기_시가총액() -> None:
    ind = indicators.compute(snapshot({"A": [20_000.0] * N}))
    assert ind.at["A", "div_yield"] == pytest.approx(1e7 * 1000 / (20_000 * 7e7))   # 100억 ÷ 1.4조


def test_모멘텀은_120영업일_전과_비교하고_이력이_짧으면_NaN() -> None:
    prices = [100.0] * (N - 121) + [100.0] + [110.0] * 119 + [150.0]
    ind = indicators.compute(snapshot({"A": prices}))
    assert ind.at["A", "momentum_6m"] == pytest.approx(0.5)
    short = indicators.compute(snapshot({"A": [100.0] * 120}))
    assert np.isnan(short.at["A", "momentum_6m"])


def test_변동성은_유효_수익률이_200개_미만이면_NaN() -> None:
    ok = indicators.compute(snapshot({"A": random_walk(N)}))
    assert 0.2 < ok.at["A", "vol_1y"] < 0.5                    # 일 2% → 연 약 32%
    few = random_walk(N)
    few[: N - 150] = [np.nan] * (N - 150)                      # 최근 150일만 거래
    assert np.isnan(indicators.compute(snapshot({"A": few})).at["A", "vol_1y"])


@pytest.mark.parametrize("prices, expected", [
    ([10, 11] * 7 + [10], 0.5),               # 상승 7번 +1, 하락 7번 −1
    (list(range(1, 16)), 1.0),                # 모두 상승
    ([10] * 15, 0.5),                         # 변화 없음
])
def test_RSI_T_RSI_01(prices, expected) -> None:
    close = pd.DataFrame({"A": [float(x) for x in prices]})
    assert indicators.rsi(close, 14)["A"] == pytest.approx(expected)


def test_상장폐지_위험_규칙() -> None:
    n = N
    snap = snapshot(
        {"OK": [20_000.0] * n, "PENNY": [900.0] * n, "SMALL": [10.0] * n, "BANK": [20_000.0] * n,
         "IMPAIR": [20_000.0] * n, "MGD": [20_000.0] * n},
        fin={"BANK": {"revenue": 1_000.0}, "OK": {"revenue": 1e9},
             "IMPAIR": {"total_equity": 20_000.0}, "SMALL": {"revenue": 1e9}},
        stocks={"BANK": {"sector_group": "금융"}, "MGD": {"is_managed": True}},
    )
    ind = indicators.compute(snap)
    rules = screening.delist_rules(ind, snap)
    assert rules["OK"] == []
    assert "D3" in rules["PENNY"]
    assert "D1" in rules["SMALL"]                               # 시총 = 10원 × 7천만 주 = 7억
    assert rules["BANK"] == []                                  # 금융은 매출 규칙 제외
    assert "D4" in rules["IMPAIR"]                              # 자본총계 2만 < 자본금 5만 × 0.5
    assert "D5" in rules["MGD"]
    assert "D5" not in screening.delist_rules(ind, snap, use_managed=False)["MGD"]


def test_공통_필터는_처음_걸린_사유_하나만_남긴다() -> None:
    snap = snapshot(
        {"GOOD": random_walk(N, 20_000, 1), "PENNY_LOSS": random_walk(N, 500, 2),
         "LOSS": random_walk(N, 20_000, 3), "NOFIN": random_walk(N, 20_000, 4)},
        fin={"PENNY_LOSS": {"net_income": -1.0}, "LOSS": {"net_income": -1.0}, "NOFIN": {"eps": None}},
    )
    r = screening.screen_common(indicators.compute(snap), snap, screening.ScreenParams(mcap_bottom_pct=0.0))
    assert list(r.universe) == ["GOOD"]
    assert r.reasons["PENNY_LOSS"] == "DELIST_RISK"            # 적자이기도 하지만 먼저 걸린 것
    assert r.reasons["LOSS"] == "LOSS_MAKING"
    assert r.reasons["NOFIN"] == "NO_FINANCIAL"


def test_소형주는_남은_종목_중_시총_하위_10퍼센트() -> None:
    # 가격이 1년 내내 같으면 거래정지로 보고 빼므로(변동성 0) 아주 조금씩 움직이게 한다
    closes = {f"S{i:02d}": [(10_000.0 + i * 1_000) * (1 + 0.001 * (k % 2)) for k in range(N)] for i in range(20)}
    snap = snapshot(closes, fin={c: {"revenue": 1e9} for c in closes})
    r = screening.screen_common(indicators.compute(snap), snap)
    assert sorted(r.reasons[r.reasons == "SMALL_CAP"].index) == ["S00", "S01"]
    assert len(r.universe) == 18


@pytest.mark.parametrize("day, fy", [("2026-03-31", 2024), ("2026-04-01", 2025), ("2026-08-19", 2025),
                                     ("2027-01-02", 2025)])
def test_기대_회계연도(day, fy) -> None:
    assert expected_fiscal_year(pd.Timestamp(day)) == fy


def test_스냅샷은_기준일_이후_데이터를_담지_않는다() -> None:
    md = MarketData.from_frames(tiny_tables(), data_version=3)
    s = md.snapshot("2026-08-18")
    assert s.close_hist.index.max() == pd.Timestamp("2026-08-18")
    assert md.snapshot().date == pd.Timestamp("2026-08-19")
    assert md.to_trading_day("2026-08-20") == pd.Timestamp("2026-08-19")   # 이후 날짜 → 직전 영업일
    assert list(md.snapshot().fin.index) == ["005930"]
    with pytest.raises(ValueError):
        md.to_trading_day("2026-08-17")                                    # 데이터보다 앞
