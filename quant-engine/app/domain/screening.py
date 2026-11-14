"""추천 대상 거르기 (docs/03 §1.6). 공통 필터는 위에서부터 차례로, 처음 걸린 사유 하나만 남긴다.

성향 필터(변동성·과열·제외 업종)는 점수 계산 뒤에 적용하므로 다음 단계(scoring)와 함께 만든다.
"""
from __future__ import annotations

from dataclasses import dataclass

import pandas as pd

from app.domain.types import Snapshot

FINANCE_GROUP = "금융"     # 매출액 규칙(D2)을 적용하지 않는 그룹 (은행·보험·증권)

NO_PRICE = "NO_PRICE"
DELIST_RISK = "DELIST_RISK"
NO_FINANCIAL = "NO_FINANCIAL"
LOSS_MAKING = "LOSS_MAKING"
INSUFFICIENT_HISTORY = "INSUFFICIENT_HISTORY"
SMALL_CAP = "SMALL_CAP"

FIN_REQUIRED = ["eps", "bps", "sps", "ceps", "net_income", "operating_income", "total_equity", "total_assets"]
PROFIT_COLUMNS = ["per", "pbr", "psr", "pcr", "roe", "roa"]
HISTORY_COLUMNS = ["momentum_6m", "vol_1y", "rsi_14", "debt_ratio", "div_yield"]


@dataclass(frozen=True)
class DelistParams:
    mcap_min: float = 5e10          # D1 시가총액 500억
    revenue_min: float = 3e10       # D2 매출 300억
    price_min: float = 1_000        # D3 동전주
    impairment: float = 0.5         # D4 자본잠식 50%


@dataclass(frozen=True)
class ScreenParams:
    delist: DelistParams = DelistParams()
    mcap_bottom_pct: float = 0.10
    use_managed: bool = True        # 백테스트는 False (관리종목 목록이 2026년 한 시점뿐)


def delist_rules(ind: pd.DataFrame, snap: Snapshot, p: DelistParams = DelistParams(),
                 use_managed: bool = True) -> pd.Series:
    """종목별 상장폐지 위험 규칙 목록 (D1~D5). 해당 없으면 빈 목록."""
    codes = ind.index
    fin = snap.fin.reindex(index=codes, columns=["total_equity", "capital"]).apply(pd.to_numeric, errors="coerce")
    stocks = snap.stocks.reindex(codes)
    flags = pd.DataFrame({
        "D1": ind["mcap"] < p.mcap_min,
        "D2": (ind["revenue_won"] < p.revenue_min) & (stocks["sector_group"] != FINANCE_GROUP),
        "D3": ind["close"] < p.price_min,
        "D4": fin["total_equity"] < fin["capital"] * p.impairment,
        "D5": stocks["is_managed"].astype("boolean").fillna(False).astype(bool) if use_managed else False,
    }, index=codes).fillna(False).astype(bool)
    return flags.apply(lambda row: [r for r, hit in row.items() if hit], axis=1)


HIGH_VOLATILITY = "HIGH_VOLATILITY"
OVERHEATED = "OVERHEATED"
EXCLUDED_SECTOR = "EXCLUDED_SECTOR"


@dataclass(frozen=True)
class ProfileScreen:
    passed: pd.Index                   # 점수 순서 유지
    reasons: pd.Series                 # 코드 → 사유
    params: dict[str, dict]            # 코드 → 문구용 숫자
    vol_pct_desc: pd.Series            # 유니버스 안 변동성 상위 백분위 (1위 = 1/n)


def screen_profile(scored: pd.DataFrame, ind: pd.DataFrame, sectors: pd.Series, *, vol_exclude_top_pct: float,
                   exclude_overheat: bool, excluded_sectors: tuple[str, ...], rsi_overheat: float = 0.70
                   ) -> ProfileScreen:
    """성향 필터 (결정 6-5). 점수 계산 뒤에 적용해서 다른 종목의 점수는 바뀌지 않는다 (FR-SCR-07)."""
    n = len(scored)
    vol_pct = ind.loc[scored.index, "vol_1y"].rank(ascending=False, method="first") / max(n, 1)
    reasons: dict[str, str] = {}
    params: dict[str, dict] = {}
    excluded = set(excluded_sectors)
    for code in scored.index:
        if vol_exclude_top_pct > 0 and vol_pct[code] <= vol_exclude_top_pct:
            reasons[code], params[code] = HIGH_VOLATILITY, {"pct": round(float(vol_pct[code]), 4)}
        elif exclude_overheat and ind.at[code, "rsi_14"] >= rsi_overheat:
            reasons[code], params[code] = OVERHEATED, {"rsi_14": round(float(ind.at[code, "rsi_14"]), 4)}
        elif sectors.get(code) in excluded:
            reasons[code], params[code] = EXCLUDED_SECTOR, {"sector": sectors.get(code)}
    passed = pd.Index([c for c in scored.index if c not in reasons])
    return ProfileScreen(passed, pd.Series(reasons, dtype=object), params, vol_pct)


@dataclass(frozen=True)
class ScreenResult:
    universe: pd.Index                 # 통과한 종목 (코드 오름차순)
    reasons: pd.Series                 # 탈락 종목 → 사유
    delist: pd.Series                  # 종목 → 상장폐지 규칙 목록 (가격 있는 종목 전부)


def screen_common(ind: pd.DataFrame, snap: Snapshot, p: ScreenParams = ScreenParams()) -> ScreenResult:
    reasons: dict[str, str] = {}
    alive = ind.index

    def mark(mask: pd.Series, reason: str) -> None:
        nonlocal alive
        hit = mask.reindex(alive).fillna(False).astype(bool)
        for code in alive[hit.to_numpy()]:
            reasons[code] = reason
        alive = alive[~hit.to_numpy()]

    mark(ind["close"].isna(), NO_PRICE)

    rules = delist_rules(ind, snap, p.delist, p.use_managed)
    mark(rules.map(bool), DELIST_RISK)

    fin = snap.fin.reindex(index=ind.index, columns=FIN_REQUIRED)
    mark(fin.isna().any(axis=1), NO_FINANCIAL)
    mark(ind[PROFIT_COLUMNS].isna().any(axis=1), LOSS_MAKING)
    mark(ind[HISTORY_COLUMNS].isna().any(axis=1), INSUFFICIENT_HISTORY)

    if len(alive):
        pct = ind.loc[alive, "mcap"].rank(method="first") / len(alive)
        mark(pct <= p.mcap_bottom_pct, SMALL_CAP)

    return ScreenResult(alive.sort_values(), pd.Series(reasons, dtype=object), rules)
