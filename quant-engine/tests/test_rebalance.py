"""리밸런싱 수량 (docs/03 §1.11, §8.5 T-REB-01, 01 FR-ALT-09) + I9 API."""
from __future__ import annotations

from datetime import date

import pandas as pd
import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.data.market_data import MarketData
from app.domain import rebalance
from app.domain.alerts import Limits, PortfolioState
from app.services import portfolio_service
from tests.factories import tiny_tables


def test_T_REB_01_종목_한도_넘은_만큼을_다른_주식에_비례로_나누고_채권은_그대로() -> None:
    # 주식 안 A 0.25 (전체 0.15), 한도 0.20
    targets = {"A": 0.15, "B": 0.27, "C": 0.18, "BOND": 0.30, "CASH": 0.10}
    t = rebalance.target_weights("R1", "A", targets, {}, {}, stock_limit=0.20, sector_limit=0.40)
    stock_total = 0.60
    assert t["A"] == pytest.approx(0.20 * stock_total)                  # 0.12
    assert t["B"] == pytest.approx(0.27 + 0.03 * 0.27 / 0.45)           # 줄인 0.03을 B:C = 27:18로
    assert t["C"] == pytest.approx(0.18 + 0.03 * 0.18 / 0.45)
    assert (t["BOND"], t["CASH"]) == (0.30, 0.10)
    assert sum(t.values()) == pytest.approx(1.0)


def test_업종_한도와_상장폐지_위험() -> None:
    targets = {"A": 0.30, "B": 0.20, "C": 0.10, "BOND": 0.30, "CASH": 0.10}
    sectors = {"A": "전기·전자", "B": "전기·전자", "C": "화학"}
    t = rebalance.target_weights("R2", "전기·전자", targets, {}, sectors, 0.5, 0.40)
    assert t["A"] + t["B"] == pytest.approx(0.40 * 0.60)
    assert t["A"] / t["B"] == pytest.approx(1.5)                          # 업종 안 비율은 유지
    assert t["C"] == pytest.approx(0.10 + 0.26)
    t7 = rebalance.target_weights("R7", "C", targets, {}, sectors, 0.5, 0.40)
    assert t7["C"] == 0.0 and t7["A"] == pytest.approx(0.30 + 0.10 * 0.6)


def test_목표에_없이_직접_추가한_종목은_R1_제안에서_그대로_둔다() -> None:
    targets = {"A": 0.40, "B": 0.10, "BOND": 0.40, "CASH": 0.10}
    current = {"A": 0.36, "B": 0.09, "X": 0.10, "BOND": 0.36, "CASH": 0.09}
    t = rebalance.target_weights("R1", "A", targets, current, {}, 0.5, 0.9)
    assert t["X"] == pytest.approx(0.10)
    assert sum(t.values()) == pytest.approx(1.0)
    # R3은 목표 그대로라 X는 판다
    assert "X" not in rebalance.target_weights("R3", "BOND", targets, current, {}, 0.5, 0.9)


def test_수량은_1주_단위로_내림하고_남는_돈은_현금() -> None:
    close = pd.Series({"A": 1_000.0, "B": 3_000.0})
    # 총 100,000원 = A 50주 50,000 + 채권 400단위×100 = 40,000 + 현금 10,000
    p = rebalance.plan({"A": 0.30, "B": 0.25, "BOND": 0.35, "CASH": 0.10}, {"A": 50}, close, 400, 100.0, 10_000)
    trades = {t["asset"]: t for t in p["trades"]}
    assert trades["A"] == {"asset": "A", "side": "SELL", "quantity": 20, "close": 1000.0, "amount": 20_000}
    assert trades["B"]["side"] == "BUY" and trades["B"]["quantity"] == 8          # 25,000 / 3,000 = 8.3 → 8
    assert trades["BOND"] == {"asset": "BOND", "side": "SELL", "amount": 5_000, "units": 50.0}
    assert [t["side"] for t in p["trades"]] == ["SELL", "SELL", "BUY"]            # 팔고 나서 산다
    assert p["after"]["quantities"] == {"A": 30, "B": 8}
    assert p["after"]["cash"] == 100_000 - 30_000 - 24_000 - 35_000
    assert p["after"]["by_asset"] == {"stock": 0.54, "bond": 0.35, "cash": 0.11}
    assert p["before"]["by_asset"] == {"stock": 0.5, "bond": 0.4, "cash": 0.1}


def _bought(real_market):  # noqa: ANN001, ANN202
    """기준 추천을 매수한 상태 (삼성전자 20주 + 추천 9종목)."""
    from app.domain.types import Existing
    from app.services import compute_service
    from tests.test_reco_golden import GOLDEN
    reco = compute_service.compute(real_market, Settings(), GOLDEN, 30_000_000, Existing({"005930": 20}),
                                   compute_service.Edits())
    items = reco["methods"][reco["method_selected"]]["items"]
    holdings = {"005930": 20, **{i["code"]: i["shares"] for i in items if i["shares"] > 0}}
    return GOLDEN, PortfolioState(holdings, reco["allocation"]["bond_units"], reco["allocation"]["cash_amount"],
                                  reco["combined"]["target_weights"], date(2026, 8, 19), date(2026, 8, 19))


def test_FR_ALT_09_제안대로_하면_목표의_1퍼센트포인트_안(real_market) -> None:  # noqa: ANN001
    golden, state = _bought(real_market)
    limits = Limits(golden.stock_limit, golden.sector_limit, golden.loss_limit)
    p = portfolio_service.rebalance_plan(real_market, Settings(), state, limits, golden, "R1", "005930")
    assert p["trades"][0]["asset"] == "005930" and p["trades"][0]["side"] == "SELL"
    for k, w in p["target_weights"].items():
        assert abs(p["after"]["weights"].get(k, 0.0) - w) <= 0.01, k
    # 삼성전자는 주식 안 한도 20% 이하로 (1주 247,500원 단위로 내림하므로 조금 아래)
    share = p["after"]["weights"]["005930"] / p["after"]["by_asset"]["stock"]
    assert 0.15 < share <= 0.20 + 1e-9
    assert p["after"]["by_asset"]["bond"] == pytest.approx(state.targets["BOND"], abs=0.001)


def test_R6_정기_점검은_같은_프로필로_새로_추천받은_비중(real_market) -> None:  # noqa: ANN001
    golden, state = _bought(real_market)
    limits = Limits(golden.stock_limit, golden.sector_limit, golden.loss_limit)
    p = portfolio_service.rebalance_plan(real_market, Settings(), state, limits, golden, "R6", "")
    # 처음부터 다시 짠 결과에는 기존 보유(삼성전자)가 순위 밖이라 빠지고, 다른 종목을 산다
    assert "005930" not in p["target_weights"]
    assert any(t["asset"] == "005930" and t["side"] == "SELL" and t["quantity"] == 20 for t in p["trades"])
    assert p["after"]["by_asset"]["stock"] <= golden.stock_cap + 0.01


def test_I9_API(client: TestClient) -> None:
    client.app.state.market = MarketData.from_frames(tiny_tables(), data_version=7)
    body = {"settings": {"grade": "NEUTRAL", "loss_limit": -0.10, "min_cash": 0.15, "stock_cap": 0.55, "n_stocks": 15,
                         "factor_weights": {"VALUE": 1.0}, "vol_exclude_top_pct": 0.10, "exclude_overheat": True,
                         "stock_limit": 0.20, "sector_limit": 0.40, "default_method": "INVERSE_VOL"},
            "holdings": [{"code": "005930", "quantity": 20}], "bond_units": 0, "cash": 4_950_000,
            "targets": {"005930": 0.3, "BOND": 0.0, "CASH": 0.7},
            "alert_type": "R3", "alert_target": "005930"}
    r = client.post("/internal/v1/portfolio/rebalance-plan", json=body)
    assert r.status_code == 200, r.text
    out = r.json()
    assert out["trades"] == [{"asset": "005930", "side": "SELL", "quantity": 8, "close": 247500.0, "amount": 1_980_000}]
    assert out["after"]["quantities"] == {"005930": 12} and out["alert_type"] == "R3"

    empty = client.post("/internal/v1/portfolio/rebalance-plan", json={**body, "holdings": [], "cash": 0})
    assert empty.status_code == 409 and empty.json()["error"]["code"] == "NOTHING_TO_REBALANCE"
