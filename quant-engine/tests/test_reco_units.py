"""추천 부품 단위 테스트 — docs/03 §8.3·8.4의 손계산 기대값."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from app.domain import allocation, scoring, screening, selection, weighting
from app.domain.orders import order_items, plan

ONLY_VALUE = {"VALUE": 1.0, "QUALITY": 0.0, "MOMENTUM": 0.0, "STABILITY": 0.0, "DIVIDEND": 0.0}


def _universe(per: dict[str, float]) -> pd.DataFrame:
    """PER만 다르고 나머지 지표는 모두 같은 유니버스."""
    df = pd.DataFrame({"per": per})
    for col in ["pbr", "psr", "pcr", "roe", "roa", "momentum_6m", "debt_ratio", "div_yield"]:
        df[col] = 1.0
    return df


# ---------------- 8.3 업종 중립 점수 ----------------
def test_업종_중립이면_그룹_안에서_순위를_매긴다() -> None:
    u = _universe({"A": 5, "B": 10, "C": 20, "D": 40})
    groups = pd.Series({"A": "G1", "B": "G1", "C": "G2", "D": "G2"})
    out = scoring.score(u, groups, ONLY_VALUE, neutral=True, min_size=2)
    assert out["s_per"].to_dict() == {"A": 0.5, "C": 0.5, "B": 0.0, "D": 0.0}
    assert list(out.index) == ["A", "C", "B", "D"]            # A·C 동점은 코드 순
    assert out.loc["C", "rank_in_group"] == 1 and out.loc["D", "group_n"] == 2


def test_전체_순위면_업종을_무시한다() -> None:
    u = _universe({"A": 5, "B": 10, "C": 20, "D": 40})
    groups = pd.Series({"A": "G1", "B": "G1", "C": "G2", "D": "G2"})
    out = scoring.score(u, groups, ONLY_VALUE, neutral=False)
    assert out["s_per"].to_dict() == {"A": 0.75, "B": 0.5, "C": 0.25, "D": 0.0}
    assert list(out.index) == ["A", "B", "C", "D"]


def test_그룹이_작으면_전체와_비교한다() -> None:
    u = _universe({"A": 5, "B": 10, "C": 20, "D": 40})
    groups = pd.Series({"A": "G1", "B": "G1", "C": "G2", "D": "G2"})
    out = scoring.score(u, groups, ONLY_VALUE, neutral=True, min_size=3)
    assert list(out.index) == ["A", "B", "C", "D"] and set(out["group"]) == {scoring.ALL}


# ---------------- 성향 필터 ----------------
def test_성향_필터는_변동성_과열_제외업종_순서이고_점수를_바꾸지_않는다() -> None:
    codes = [f"S{i}" for i in range(10)]
    ind = pd.DataFrame({"vol_1y": np.linspace(0.1, 1.0, 10), "rsi_14": [0.5] * 9 + [0.9]}, index=codes)
    ind.loc["S3", "rsi_14"] = 0.75
    scored = pd.DataFrame({"total": np.linspace(1, 0, 10)}, index=codes)
    sectors = pd.Series({c: "화학" for c in codes} | {"S5": "은행"})
    prof = screening.screen_profile(scored, ind, sectors, vol_exclude_top_pct=0.10, exclude_overheat=True,
                                    excluded_sectors=("은행",))
    assert prof.reasons.to_dict() == {"S3": "OVERHEATED", "S5": "EXCLUDED_SECTOR", "S9": "HIGH_VOLATILITY"}
    assert list(prof.passed) == ["S0", "S1", "S2", "S4", "S6", "S7", "S8"]
    assert scored["total"].tolist() == list(np.linspace(1, 0, 10))   # 점수는 그대로


# ---------------- 8.4 선택·비중·배분·주문 ----------------
def test_T_SEL_01_주식_몫이_작으면_종목_수를_줄인다() -> None:
    close = pd.Series({"A": 10_000.0, "B": 10_000.0, "C": 10_000.0})
    sector = pd.Series({"A": "x", "B": "y", "C": "z"})
    sel = selection.select(["A", "B", "C"], close, sector, 15, 1_000_000, {}, {}, 1.0)
    assert sel.n_target == 2 and sel.picks == ["A", "B"]


def test_T_SEL_02_1주_가격이_배정액보다_크면_건너뛴다() -> None:
    close = pd.Series({"A": 1_500_000.0, "B": 10_000.0})
    sel = selection.select(["A", "B"], close, pd.Series({"A": "x", "B": "y"}), 1, 700_000, {}, {}, 1.0)
    assert sel.skipped == {"A": "PRICE_TOO_HIGH"} and sel.picks == ["B"]


def test_기존_보유와_합쳐_업종_한도를_넘으면_건너뛴다() -> None:
    close = pd.Series({"A": 10_000.0, "B": 10_000.0})
    sector = pd.Series({"A": "전기·전자", "B": "화학"})
    # 기존 전기·전자 900만 + 새 돈 1,000만(2종목, 종목당 500만)
    # A: (900+500)/1,900 = 74% > 40% → 건너뜀,  B(화학): 500/1,900 = 26% → 담음
    sel = selection.select(["A", "B"], close, sector, 2, 10_000_000, {"005930": 9_000_000},
                           {"전기·전자": 9_000_000}, 0.40)
    assert sel.skipped == {"A": "SECTOR_LIMIT"} and sel.picks == ["B"]


def test_MDD는_첫날_하락도_센다() -> None:
    assert allocation.mdd(np.array([-0.2, 0.0, 0.0])) == pytest.approx(-0.2)
    assert allocation.mdd(np.array([0.2, -0.25])) == pytest.approx(-0.25)
    assert allocation.mdd(np.array([0.1, 0.1])) == 0.0


def test_T_W_01_상한을_넘는_만큼_나머지에_나눈다() -> None:
    out = weighting.cap_weights(pd.Series([0.5, 0.3, 0.2]), 0.4)
    assert out.tolist() == pytest.approx([0.4, 0.36, 0.24])


def test_T_W_02_변동성_역수() -> None:
    w = weighting.weights_all(["A", "B"], pd.Series({"A": 0.1, "B": 0.2}), pd.DataFrame(), 1.0)
    assert w.by_method["INVERSE_VOL"].tolist() == [0.6667, 0.3333]
    assert w.min_variance_fallback                               # 수익률 이력이 없어 최소분산 대신 변동성 역수


def test_T_W_03_종목당_한도가_1_나누기_N보다_작으면_올린다() -> None:
    w = weighting.weights_all(list("ABCDE"), pd.Series(0.2, index=list("ABCDE")), pd.DataFrame(), 0.15)
    assert w.by_method["EQUAL"].tolist() == [0.2] * 5


def test_최소분산은_분산이_작은_자산에_더_싣는다() -> None:
    x = weighting.solve_min_variance(np.diag([0.01, 0.04]))
    assert x.tolist() == pytest.approx([0.8, 0.2], abs=1e-4)


def test_비중_반올림_후_합은_정확히_1() -> None:
    r = weighting.round_weights(pd.Series([1 / 3, 1 / 3, 1 / 3]))
    assert r.sum() == pytest.approx(1.0, abs=1e-12) and r.round(4).sum() == 1.0


def test_T_ALC_01_손실_한도_안에서_주식_최대_다음_채권_최대() -> None:
    m = allocation.choose_mix(np.array([-0.20, 0, 0]), np.zeros(3), 0.0, 0.9, -0.10)
    assert (m.stock, m.bond, m.cash, m.binding) == (0.50, 0.50, 0.0, "LOSS_LIMIT")


def test_T_ALC_02_채권도_한도를_깨면_현금으로() -> None:
    m = allocation.choose_mix(np.array([-0.20, 0, 0]), np.array([-0.06, 0, 0]), 0.0, 0.9, -0.05)
    assert (m.stock, m.bond, m.cash) == (0.25, 0.0, 0.75)


def test_T_ALC_03_한도가_없으면_주식은_상한_나머지는_채권() -> None:
    m = allocation.choose_mix(np.array([-0.5]), np.zeros(1), 0.15, 0.55, None)
    assert (m.stock, m.bond, m.cash, m.binding) == (0.55, 0.30, 0.15, "CAP")


def test_한도가_너무_낮으면_모두_현금() -> None:
    m = allocation.choose_mix(np.array([-0.20]), np.array([-0.10]), 0.0, 0.9, -1e-9)
    assert (m.stock, m.bond, m.cash) == (0.0, 0.0, 1.0)


def test_T_ORD_01_정수_주식_수와_남는_돈() -> None:
    items = order_items(1_000_000, pd.Series({"A": 0.5, "B": 0.5}), pd.Series({"A": 300_000.0, "B": 70_000.0}))
    assert [(i.code, i.shares, i.spend) for i in items] == [("A", 1, 300_000), ("B", 7, 490_000)]
    p = plan(1_000_000, 1.0, 0.0, pd.Series({"A": 0.5, "B": 0.5}), pd.Series({"A": 300_000.0, "B": 70_000.0}), 100.0)
    assert p.cash_from_rounding == 210_000 and p.cash_amount == 210_000


def test_주문은_투자금을_정확히_나눈다() -> None:
    p = plan(30_000_000, 0.47, 0.38, pd.Series({"A": 0.6, "B": 0.4}),
             pd.Series({"A": 123_400.0, "B": 9_870.0}), 102.59)
    assert p.stock_amount + p.bond_amount + p.cash_amount == 30_000_000
    assert p.bond_amount == 11_400_000 and p.bond_units == pytest.approx(11_400_000 / 102.59)


def test_채권_금리_충격도_한도_안이어야_한다() -> None:
    # 주식 6% × -50% = -3% (한도와 같음). 과거 채권은 손실이 없었지만 금리 충격 5.6%를 넣으면 채권은 53%까지만
    m = allocation.choose_mix(np.array([-0.5]), np.zeros(1), 0.0, 0.9, -0.03, bond_shock_loss=0.056)
    assert (m.stock, m.bond) == (0.06, 0.53)
    assert allocation.choose_mix(np.array([-0.5]), np.zeros(1), 0.0, 0.9, -0.03).bond == 0.94   # 충격 없으면 나머지 전부 채권
