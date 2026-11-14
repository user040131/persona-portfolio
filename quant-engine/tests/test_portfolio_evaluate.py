"""포트폴리오 평가·알림 조건 (docs/03 §1.10, §8.5 T-ALT-01~05) + I8 API."""
from __future__ import annotations

from datetime import date

import pandas as pd
import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.data.market_data import MarketData
from app.domain import alerts
from app.domain.alerts import Limits, PortfolioState
from app.services import portfolio_service
from tests.factories import tiny_tables

LIMITS = Limits(stock_limit=0.20, sector_limit=0.40, loss_limit=-0.10)


def _market(closes: dict[str, list[float]], sectors: dict[str, str] | None = None, end: str = "2026-08-19",
            bond: list[float] | None = None):
    n = len(next(iter(closes.values())))
    days = pd.bdate_range(end=end, periods=n)
    close = pd.DataFrame(closes, index=days)
    bidx = pd.Series(bond or [100.0] * n, index=days)
    sec = {c: (sectors or {}).get(c, "업종" + c) for c in closes}
    return days, close, bidx, sec


def _eval(state: PortfolioState, closes, limits: Limits = LIMITS, delist=None, **kw):  # noqa: ANN001
    days, close, bidx, sec = _market(closes, **kw)
    return alerts.evaluate(state, limits, days[-1], close, bidx, sec, delist or {}, days)


def _types(r: dict) -> list[tuple[str, str]]:
    return [(c["type"], c["target"]) for c in r["conditions"]]


def test_평가금액과_비중() -> None:
    r = _eval(PortfolioState({"A": 10, "B": 30}, bond_units=50, cash=2_000), {"A": [900, 1000], "B": [100, 100]})
    # A 10,000 + B 3,000 + 채권 50×100 = 5,000 + 현금 2,000 = 20,000
    assert r["total_value"] == 20_000
    assert r["by_asset"] == {"stock": 0.65, "bond": 0.25, "cash": 0.1}
    a = {x["asset"]: x for x in r["assets"]}
    assert a["A"]["value"] == 10_000 and a["A"]["weight"] == 0.5 and a["A"]["weight_in_stock"] == 0.7692
    assert [x["asset"] for x in r["assets"]] == ["A", "B", "BOND", "CASH"]   # 금액 큰 종목부터, 채권·현금은 끝
    assert r["as_of"] == "2026-08-19"


def test_거래가_없던_날은_직전_종가로_평가한다() -> None:
    r = _eval(PortfolioState({"A": 10}), {"A": [1000, float("nan")]})
    assert r["assets"][0]["close"] == 1000 and r["total_value"] == 10_000


def test_T_ALT_01_종목_한도를_넘으면_R1() -> None:
    # 주식 안 비중 A 0.21 (나머지는 다른 업종 소형 종목들)
    closes = {"A": [21.0, 21.0], **{f"S{i}": [1.0, 1.0] for i in range(79)}}
    state = PortfolioState({"A": 100, **{f"S{i}": 100 for i in range(79)}})
    r = _eval(state, closes)
    assert ("R1", "A") in _types(r)
    assert r["conditions"][0]["detail"] == {"weight": 0.21, "limit": 0.20}


def test_업종_한도를_넘으면_R2() -> None:
    closes = {"A": [1.0, 1.0], "B": [1.0, 1.0], "C": [1.0, 1.0], "D": [1.0, 1.0], "E": [1.0, 1.0]}
    sectors = {"A": "전기·전자", "B": "전기·전자", "C": "화학", "D": "금속", "E": "유통"}
    r = _eval(PortfolioState({c: 100 for c in closes}), closes, sectors=sectors)
    # 전기·전자 0.40은 한도와 같아 알림 없음, 0.4 초과만
    assert not [t for t in _types(r) if t[0] == "R2"]
    r = _eval(PortfolioState({"A": 101, "B": 100, "C": 100, "D": 100, "E": 99}), closes, sectors=sectors)
    assert ("R2", "전기·전자") in _types(r)


def test_T_ALT_02_목표에서_벌어진_자산은_R3_안전자산이_줄면_R4() -> None:
    # 채권 목표 0.30, 현재 0.24
    state = PortfolioState({"A": 66}, bond_units=24, cash=1_000,
                           targets={"A": 0.60, "BOND": 0.30, "CASH": 0.10}, purchased_at=date(2026, 8, 18))
    r = _eval(state, {"A": [100, 100]})
    assert ("R3", "BOND") in _types(r) and ("R3", "A") in _types(r)
    assert ("R4", "") in _types(r)
    r4 = next(c for c in r["conditions"] if c["type"] == "R4")
    assert r4["detail"] == {"current": 0.34, "target": 0.4}


def test_목표가_없으면_R3_R4를_보지_않는다() -> None:
    r = _eval(PortfolioState({"A": 10}, cash=0), {"A": [100, 100]})
    assert not [t for t in _types(r) if t[0] in ("R3", "R4")]


def test_T_ALT_03_손실_한도의_80퍼센트_하락이면_R5() -> None:
    state = PortfolioState({"A": 100}, purchased_at=date(2026, 8, 17))
    r = _eval(state, {"A": [100.0, 100.0, 92.0]})       # 고점 대비 -8% = 한도 -10% × 0.8
    assert ("R5", "") in _types(r) and r["drawdown"] == -0.08
    r = _eval(state, {"A": [100.0, 100.0, 93.0]})
    assert ("R5", "") not in _types(r)


def test_R5는_보유를_바꾼_뒤부터만_본다() -> None:
    state = PortfolioState({"A": 100}, purchased_at=date(2026, 8, 17), holdings_changed_at=date(2026, 8, 19))
    r = _eval(state, {"A": [100.0, 100.0, 80.0]})
    assert r["drawdown"] == 0.0 and ("R5", "") not in _types(r)


def test_T_ALT_04_손실_한도가_없으면_R5_없음() -> None:
    state = PortfolioState({"A": 100}, purchased_at=date(2026, 8, 17))
    r = _eval(state, {"A": [100.0, 100.0, 50.0]}, limits=Limits(0.2, 0.4, None))
    assert ("R5", "") not in _types(r) and r["drawdown"] == -0.5


def test_T_ALT_05_4월_첫_영업일_이후_한_해_한_번_R6() -> None:
    closes = {"A": [100.0] * 30}
    before = PortfolioState({"A": 1}, purchased_at=date(2025, 11, 3))
    r = _eval(before, closes, end="2026-04-15")
    assert ("R6", "") in _types(r) and r["conditions"][-1]["detail"] == {"year": 2026}
    done = PortfolioState({"A": 1}, purchased_at=date(2025, 11, 3), last_r6_year=2026)
    assert ("R6", "") not in _types(_eval(done, closes, end="2026-04-15"))
    after_april = PortfolioState({"A": 1}, purchased_at=date(2026, 4, 10))
    assert ("R6", "") not in _types(_eval(after_april, closes, end="2026-04-15"))
    assert ("R6", "") not in _types(_eval(before, closes, end="2026-03-31"))


def test_상장폐지_위험_종목은_R7() -> None:
    r = _eval(PortfolioState({"A": 1, "B": 1}), {"A": [1.0, 1.0], "B": [1.0, 1.0]}, delist={"B": ["D3"]})
    assert ("R7", "B") in _types(r)
    assert next(a for a in r["assets"] if a["asset"] == "B")["delist_rules"] == ["D3"]


def test_빈_포트폴리오() -> None:
    r = _eval(PortfolioState({}), {"A": [1.0, 1.0]})
    assert r["total_value"] == 0 and r["by_asset"] == {"stock": 0.0, "bond": 0.0, "cash": 0.0}


# ---------------------------------------------------------------- 실제 데이터 · API


def test_기준_추천을_매수한_포트폴리오(real_market) -> None:  # noqa: ANN001
    """골든 추천 매수 후 상태: 삼성전자 20주 + 추천 9종목. 매수 당일이라 R3·R4·R5는 없어야 한다."""
    from app.domain.types import Existing
    from app.services import compute_service
    from tests.test_reco_golden import GOLDEN
    reco = compute_service.compute(real_market, Settings(), GOLDEN, 30_000_000, Existing({"005930": 20}),
                                   compute_service.Edits())
    items = reco["methods"][reco["method_selected"]]["items"]
    holdings = {"005930": 20, **{i["code"]: i["shares"] for i in items if i["shares"] > 0}}
    state = PortfolioState(holdings, reco["allocation"]["bond_units"], reco["allocation"]["cash_amount"],
                           reco["combined"]["target_weights"], date(2026, 8, 19), date(2026, 8, 19))
    r = portfolio_service.evaluate(real_market, Settings(), state, LIMITS)
    assert abs(r["total_value"] - (30_000_000 + 20 * 247_500)) < 10
    assert r["by_asset"] == pytest.approx(reco["combined"]["by_asset"], abs=0.001)   # 반올림 차이만
    types = {c["type"] for c in r["conditions"]}
    assert not types & {"R3", "R4", "R5", "R6", "R7"}
    assert ("R1", "005930") in {(c["type"], c["target"]) for c in r["conditions"]}   # 삼성전자가 주식의 절반


def test_I8_API(client: TestClient) -> None:
    client.app.state.market = MarketData.from_frames(tiny_tables(), data_version=7)
    body = {"settings": {"grade": "NEUTRAL", "loss_limit": -0.10, "min_cash": 0.15, "stock_cap": 0.55, "n_stocks": 15,
                         "factor_weights": {"VALUE": 1.0}, "vol_exclude_top_pct": 0.10, "exclude_overheat": True,
                         "stock_limit": 0.20, "sector_limit": 0.40, "default_method": "INVERSE_VOL"},
            "holdings": [{"code": "005930", "quantity": 20}], "bond_units": 100, "cash": 4_320_000,
            "targets": {"005930": 0.5, "BOND": 0.0, "CASH": 0.5},
            "purchased_at": "2026-08-19", "holdings_changed_at": "2026-08-19"}
    r = client.post("/internal/v1/portfolio/evaluate", json=body)
    assert r.status_code == 200, r.text
    out = r.json()
    assert out["data_version"] == 7 and out["total_value"] == 20 * 247_500 + round(100 * 99.73) + 4_320_000
    assert out["assets"][0] == {"asset": "005930", "quantity": 20, "close": 247500.0, "value": 4_950_000,
                                "weight": 0.5334, "weight_in_stock": 1.0,
                                "sector": "전기·전자", "delist_rules": []}

    bad = client.post("/internal/v1/portfolio/evaluate", json={**body, "holdings": [{"code": "999999", "quantity": 1}]})
    assert bad.status_code == 422 and bad.json()["error"]["code"] == "UNKNOWN_STOCK"
