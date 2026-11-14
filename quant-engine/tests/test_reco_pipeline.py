"""추천 파이프라인 전체 (합성 시장 7년 × 40종목) + I6 API."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest
from fastapi.testclient import TestClient

from app.domain import indicators, recommend, screening
from app.domain.types import Edits, Existing, ProfileSettings, Snapshot
from app.services import compute_service

SECTORS = ["화학", "전기·전자", "유통", "금속"]


@pytest.fixture(scope="module")
def inputs() -> recommend.Inputs:
    rng = np.random.default_rng(42)
    n_days, codes = 1800, [f"{i:06d}" for i in range(1, 41)]
    idx = pd.bdate_range(end="2026-08-19", periods=n_days)
    vols = np.linspace(0.01, 0.03, len(codes))
    close = pd.DataFrame({c: 20_000 * np.cumprod(1 + rng.normal(0.0004, v, n_days)) for c, v in zip(codes, vols)},
                         index=idx)
    fin = pd.DataFrame({c: dict(total_assets=1e9, total_liabilities=3e8 * (1 + i / 40), total_equity=7e8,
                                capital=5e7, revenue=5e8, operating_income=8e7, net_income=6e7 * (1 + i / 20),
                                dividends=-1e7, eps=1_000.0 + 50 * i, bps=10_000.0, sps=5_000.0, ceps=1_500.0)
                        for i, c in enumerate(codes)}).T
    stocks = pd.DataFrame({c: {"name": f"종목{c[-2:]}", "sector": SECTORS[i % 4], "sector_group": SECTORS[i % 4],
                               "is_managed": False} for i, c in enumerate(codes)}).T
    snap = Snapshot(idx[-1], close, close.pct_change(fill_method=None), pd.Series(0.0, index=idx),
                    pd.Series(0.0001, index=idx), fin, stocks)
    ind = indicators.compute(snap)
    common = screening.screen_common(ind, snap, screening.ScreenParams(mcap_bottom_pct=0.0))
    return recommend.Inputs(snap, ind, common, bond_index=105.0, data_version=9)


def settings(**kw) -> ProfileSettings:
    base = dict(grade="NEUTRAL", loss_limit=-0.10, min_cash=0.15, stock_cap=0.55, n_stocks=10,
                factor_weights={"VALUE": 0.3, "QUALITY": 0.3, "MOMENTUM": 0.25, "STABILITY": 0.15, "DIVIDEND": 0.0},
                vol_exclude_top_pct=0.10, exclude_overheat=False, stock_limit=0.20, sector_limit=0.40,
                default_method="INVERSE_VOL", excluded_sectors=())
    return ProfileSettings(**{**base, **kw})


def test_결과_형식과_금액_항등식(inputs) -> None:
    r = recommend.compute(inputs, settings(), 30_000_000, Existing())
    a = r["allocation"]
    assert round(a["stock"] + a["bond"] + a["cash"], 2) == 1.0
    assert a["stock_amount"] + a["bond_amount"] + a["cash_amount"] == 30_000_000
    assert a["expected_mdd"] >= -0.10 - 1e-9                      # 손실 한도 안
    for m in ("EQUAL", "INVERSE_VOL", "MIN_VARIANCE"):
        items = r["methods"][m]["items"]
        assert sum(i["weight"] for i in items) == pytest.approx(1.0, abs=1e-9)
        assert all(i["spend"] <= i["amount"] for i in items)
        assert all(i["weight"] <= 0.20 + 1e-9 for i in items)     # 종목당 한도
    assert len(r["screening"]) == 40
    picked = {c for c, v in r["screening"].items() if v["stage"] == "PICKED"}
    assert picked == {s["code"] for s in r["stocks"]}
    assert r["portfolio_facts"]["skipped"].get("HIGH_VOLATILITY") == 4      # 40개 중 변동성 상위 10%
    assert r["stocks"][0]["facts"]["role"]["code"] == "NO_HOLDINGS"


def test_같은_입력이면_같은_결과(inputs) -> None:
    assert recommend.compute(inputs, settings(), 30_000_000, Existing()) == \
        recommend.compute(inputs, settings(), 30_000_000, Existing())


def test_종목을_빼면_다음_순위로_채운다(inputs) -> None:
    first = recommend.compute(inputs, settings(), 30_000_000, Existing())
    top = first["stocks"][0]["code"]
    second = recommend.compute(inputs, settings(), 30_000_000, Existing(), Edits(removed_codes=(top,)))
    assert top not in {s["code"] for s in second["stocks"]}
    assert second["screening"][top] == {"stage": "SELECTION", "reason": "USER_REMOVED", "params": {}}
    assert len(second["stocks"]) == len(first["stocks"])


def test_안전자산을_직접_정하면_따르고_한도를_넘으면_경고(inputs) -> None:
    r = recommend.compute(inputs, settings(loss_limit=-0.01), 30_000_000, Existing(), Edits(safe_ratio=0.2))
    assert r["allocation"]["stock"] == 0.8 and r["portfolio_facts"]["stock_binding"] == "USER"
    assert any(w["type"] == "LOSS_LIMIT_EXCEEDED" for w in r["warnings"])


def test_한도가_없으면_주식은_상한(inputs) -> None:
    r = recommend.compute(inputs, settings(loss_limit=None, stock_cap=0.9, min_cash=0.0), 30_000_000, Existing())
    assert (r["allocation"]["stock"], r["portfolio_facts"]["stock_binding"]) == (0.9, "CAP")


def test_기존_보유는_합산되고_역할이_보인다(inputs) -> None:
    held = inputs.common.universe[0]
    r = recommend.compute(inputs, settings(), 30_000_000, Existing({held: 100}, bond_units=10.0, cash=1_000))
    tw = r["combined"]["target_weights"]
    assert held in tw and tw["BOND"] > 0 and sum(tw.values()) == pytest.approx(1.0, abs=1e-3)
    roles = {s["facts"]["role"]["code"] for s in r["stocks"]}
    assert roles <= {"HELD", "DIVERSIFY", "OVERLAP"} and "NO_HOLDINGS" not in roles


def test_주식_몫이_종목당_최소_금액도_안_되면_주식_0퍼센트(inputs) -> None:
    # 80만 원 × 상한 55% = 44만 원 < 50만 원 → 담을 종목 없음
    r = recommend.compute(inputs, settings(), 800_000, Existing())
    a = r["allocation"]
    assert (a["stock"], r["stocks"], r["portfolio_facts"]["stock_binding"]) == (0.0, [], "LOSS_LIMIT")
    assert a["stock_amount"] == 0 and a["bond_amount"] + a["cash_amount"] == 800_000
    assert any(w["type"] == "NO_STOCK_ALLOCATION" for w in r["warnings"])


def test_모르는_보유_종목이면_오류(inputs) -> None:
    with pytest.raises(recommend.UnknownStocks):
        recommend.compute(inputs, settings(), 30_000_000, Existing({"999999": 1}))


def test_모든_업종을_빼면_후보_없음(inputs) -> None:
    with pytest.raises(recommend.NoCandidates):
        recommend.compute(inputs, settings(excluded_sectors=tuple(SECTORS)), 30_000_000, Existing())


# ---------------- I6 API ----------------
BODY = {
    "settings": {"grade": "NEUTRAL", "loss_limit": -0.10, "min_cash": 0.15, "stock_cap": 0.55, "n_stocks": 10,
                 "factor_weights": {"VALUE": 0.3, "QUALITY": 0.3, "MOMENTUM": 0.25, "STABILITY": 0.15, "DIVIDEND": 0.0},
                 "vol_exclude_top_pct": 0.10, "exclude_overheat": False, "stock_limit": 0.20, "sector_limit": 0.40,
                 "default_method": "INVERSE_VOL", "excluded_sectors": []},
    "invest_amount": 30_000_000,
    "existing": {"holdings": [], "bond_units": 0, "cash": 0},
    "edits": {"removed_codes": [], "safe_ratio": None, "method": None},
}


@pytest.fixture
def api(client: TestClient, inputs, monkeypatch) -> TestClient:
    client.app.state.market = object()          # 데이터가 있다고 표시만 (계산 입력은 아래로 바꿔 끼움)
    monkeypatch.setattr(compute_service, "latest_inputs", lambda md, s: inputs)
    return client


def test_API_계산_결과(api: TestClient) -> None:
    r = api.post("/internal/v1/recommendations/compute", json=BODY)
    assert r.status_code == 200
    body = r.json()
    assert body["data_version"] == 9 and body["method_selected"] == "INVERSE_VOL"
    assert set(body) >= {"allocation", "methods", "stocks", "portfolio_facts", "screening", "combined", "warnings"}


def test_API_시장_데이터_없으면_503(client: TestClient) -> None:
    assert client.post("/internal/v1/recommendations/compute", json=BODY).status_code == 503


def test_API_가중치_합이_1이_아니면_422(api: TestClient) -> None:
    body = {**BODY, "settings": {**BODY["settings"], "factor_weights": {"VALUE": 0.5}}}
    r = api.post("/internal/v1/recommendations/compute", json=body)
    assert r.status_code == 422 and r.json()["error"]["code"] == "VALIDATION_ERROR"


def test_API_모르는_종목은_422_UNKNOWN_STOCK(api: TestClient) -> None:
    body = {**BODY, "existing": {"holdings": [{"code": "999999", "quantity": 1}]}}
    r = api.post("/internal/v1/recommendations/compute", json=body)
    assert r.status_code == 422 and r.json()["error"] == {"code": "UNKNOWN_STOCK", "message": r.json()["error"]["message"],
                                                         "detail": {"codes": ["999999"]}}


def test_API_후보가_없으면_409(api: TestClient) -> None:
    body = {**BODY, "settings": {**BODY["settings"], "excluded_sectors": SECTORS}}
    r = api.post("/internal/v1/recommendations/compute", json=body)
    assert r.status_code == 409 and r.json()["error"]["code"] == "INSUFFICIENT_UNIVERSE"
