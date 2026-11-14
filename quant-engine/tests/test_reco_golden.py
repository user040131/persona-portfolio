"""실제 데이터(2026-08-19)로 추천 계산 — 골든 프로필 결과를 고정한다 (회귀 테스트).

숫자는 설계 원칙으로 손계산한 값이 아니라, 처음 계산한 결과를 검토해 고정한 값이다.
계산 규칙을 바꾸면 이 값도 근거와 함께 갱신한다.
"""
from __future__ import annotations

import time

import pytest

from app.config import Settings
from app.domain.types import Existing, ProfileSettings
from app.services import compute_service

GOLDEN = ProfileSettings(  # 01 FR-ONB 골든 프로필 (위험중립형, -10%, 현금 15%, 주식 상한 55%, 15종목)
    grade="NEUTRAL", loss_limit=-0.10, min_cash=0.15, stock_cap=0.55, n_stocks=15,
    factor_weights={"VALUE": 0.2727, "QUALITY": 0.2727, "MOMENTUM": 0.2273, "STABILITY": 0.1364, "DIVIDEND": 0.0909},
    vol_exclude_top_pct=0.10, exclude_overheat=True, stock_limit=0.20, sector_limit=0.40,
    default_method="INVERSE_VOL")


def test_골든_프로필_3천만원_삼성전자_20주(real_market) -> None:
    started = time.perf_counter()
    r = compute_service.compute(real_market, Settings(), GOLDEN, 30_000_000, Existing({"005930": 20}),
                                compute_service.Edits())
    elapsed = time.perf_counter() - started
    assert elapsed < 5.0                                           # NFR-01 추천 1회 ≤ 5초

    a, f = r["allocation"], r["portfolio_facts"]
    # 한도 -10%에 여유 70%(-7%), 과거 전체(2010~), 채권 금리 충격을 넣은 결과 (백테스트 D 분석으로 바꿈)
    assert (a["stock"], a["bond"], a["cash"]) == (0.08, 0.53, 0.39)
    assert f["stock_binding"] == "LOSS_LIMIT" and -0.07 <= a["expected_mdd"] < -0.06
    assert a["lookback"] == {"start": "2010-01-04", "end": "2026-08-19"}
    assert a["stock_amount"] + a["bond_amount"] + a["cash_amount"] == 30_000_000
    assert (f["n_final"], f["n_reduced"]) == (4, True)              # 주식 240만 원 ÷ 50만 원 = 4종목
    picks = [s["code"] for s in r["stocks"]]
    assert len(picks) == 4 and "005930" not in picks
    assert r["screening"]["005930"]["reason"] == "RANK_OUT"
    assert all(r["screening"][c]["stage"] == "PICKED" for c in picks)
    assert {w["type"] for w in r["warnings"]} == {"N_REDUCED", "COMBINED_LOSS_LIMIT"}  # 삼성전자 보유분 때문에 전체로는 한도 초과


def test_추천_종목은_상장폐지_위험_관리종목이_아니다(real_market) -> None:
    r = compute_service.compute(real_market, Settings(), GOLDEN, 50_000_000, Existing(), compute_service.Edits())
    items = {s.code: s for s in __import__("app.services.stock_service", fromlist=["x"]).latest_items(real_market, Settings())}
    for s in r["stocks"]:
        assert items[s["code"]].delist_rules == [] and not items[s["code"]].is_managed


def test_공격형_한도_없음이면_주식_90퍼센트(real_market) -> None:
    aggressive = ProfileSettings(**{**GOLDEN.__dict__, "grade": "AGGRESSIVE", "loss_limit": None, "min_cash": 0.0,
                                    "stock_cap": 0.90, "vol_exclude_top_pct": 0.0, "exclude_overheat": False,
                                    "default_method": "EQUAL", "stock_limit": 0.25, "sector_limit": 0.50})
    r = compute_service.compute(real_market, Settings(), aggressive, 30_000_000, Existing(), compute_service.Edits())
    assert (r["allocation"]["stock"], r["allocation"]["bond"]) == (0.90, 0.10)
    assert r["portfolio_facts"]["n_final"] == 15


@pytest.mark.parametrize("limit, max_stock", [(-0.03, 0.10), (-0.05, 0.15), (-0.20, 0.55)])
def test_손실_한도가_낮을수록_주식이_줄고_한도를_지킨다(real_market, limit, max_stock) -> None:
    s = ProfileSettings(**{**GOLDEN.__dict__, "loss_limit": limit})
    r = compute_service.compute(real_market, Settings(), s, 30_000_000, Existing(), compute_service.Edits())
    assert r["allocation"]["stock"] <= max_stock
    assert r["allocation"]["expected_mdd"] >= limit - 1e-9
