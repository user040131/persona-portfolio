"""포트폴리오 평가와 알림 조건 R1~R7 (docs/03 §1.10, 01 FR-PF-02·FR-ALT-*).

엔진은 '지금 어떤 조건이 성립하는가'만 돌려준다. 알림을 열고 닫는 상태는 Spring이 관리한다(§6.5).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date

import pandas as pd

BOND, CASH = "BOND", "CASH"


@dataclass(frozen=True)
class AlertParams:
    rebalance_band: float = 0.05      # R3·R4 (01 §5)
    loss_alert_ratio: float = 0.8     # R5: 손실 한도의 80%에 닿으면


@dataclass(frozen=True)
class PortfolioState:
    holdings: dict[str, int]                       # 종목코드 → 수량
    bond_units: float = 0.0
    cash: int = 0
    targets: dict[str, float] = field(default_factory=dict)   # 매수 완료 때 저장한 목표 비중
    purchased_at: date | None = None
    holdings_changed_at: date | None = None
    last_r6_year: int | None = None


@dataclass(frozen=True)
class Limits:
    stock_limit: float
    sector_limit: float
    loss_limit: float | None


def _r(x: float, n: int = 4) -> float:
    return round(float(x), n)


def last_closes(close_hist: pd.DataFrame, codes: list[str]) -> pd.Series:
    """기준일까지 마지막 종가. 그날 거래가 없었으면(거래정지 등) 그 전 마지막 종가를 쓴다."""
    sub = close_hist.reindex(columns=codes)
    return sub.ffill().iloc[-1] if len(sub) else pd.Series(float("nan"), index=codes)


def april_first_trading_day(trading_days: pd.DatetimeIndex, year: int) -> pd.Timestamp | None:
    i = trading_days.searchsorted(pd.Timestamp(year, 4, 1))
    return trading_days[i] if i < len(trading_days) and trading_days[i].year == year else None


def evaluate(state: PortfolioState, limits: Limits, t: pd.Timestamp, close_hist: pd.DataFrame,
             bond_idx: pd.Series, sectors: dict[str, str], delist: dict[str, list[str]],
             trading_days: pd.DatetimeIndex, p: AlertParams = AlertParams()) -> dict:
    """close_hist·bond_idx는 t까지만 들어 있어야 한다."""
    codes = sorted(state.holdings)
    close = last_closes(close_hist, codes)
    bidx = float(bond_idx.loc[t])

    stock_value = {c: state.holdings[c] * float(close[c]) if pd.notna(close[c]) else 0.0 for c in codes}
    value = {**stock_value, BOND: state.bond_units * bidx, CASH: float(state.cash)}
    total = sum(value.values())
    cur = {k: (v / total if total > 0 else 0.0) for k, v in value.items()}
    stock_total = sum(stock_value.values())
    w_in = {c: (v / stock_total if stock_total > 0 else 0.0) for c, v in stock_value.items()}
    by_sector: dict[str, float] = {}
    for c in codes:
        by_sector[sectors[c]] = by_sector.get(sectors[c], 0.0) + w_in[c]

    conditions: list[dict] = []

    # R1 종목 한도, R2 업종 한도 (주식 안 기준)
    for c in codes:
        if w_in[c] > limits.stock_limit:
            conditions.append({"type": "R1", "target": c, "detail": {"weight": _r(w_in[c]), "limit": limits.stock_limit}})
    for s, w in sorted(by_sector.items()):
        if w > limits.sector_limit:
            conditions.append({"type": "R2", "target": s, "detail": {"weight": _r(w), "limit": limits.sector_limit}})

    # R3 자산별 목표와의 차이, R4 안전자산 부족 (목표가 있을 때만 = 매수 완료 후)
    if state.targets:
        for a in sorted(set(state.targets) | set(value)):
            gap = cur.get(a, 0.0) - state.targets.get(a, 0.0)
            if abs(gap) > p.rebalance_band:
                conditions.append({"type": "R3", "target": a,
                                   "detail": {"current": _r(cur.get(a, 0.0)), "target": _r(state.targets.get(a, 0.0))}})
        safe_now = cur[BOND] + cur[CASH]
        safe_target = state.targets.get(BOND, 0.0) + state.targets.get(CASH, 0.0)
        if safe_now < safe_target - p.rebalance_band:
            conditions.append({"type": "R4", "target": "", "detail": {"current": _r(safe_now), "target": _r(safe_target)}})

    # R5 고점 대비 하락: 마지막으로 보유를 바꾼 뒤부터 지금 수량을 고정해 매일 총가치를 잰다
    drawdown = None
    since = max((d for d in (state.purchased_at, state.holdings_changed_at) if d is not None), default=None)
    if since is not None:
        days = close_hist.index[close_hist.index >= pd.Timestamp(since)]
        if len(days):
            px = close_hist.reindex(columns=codes).ffill().loc[days]
            qty = pd.Series(state.holdings, dtype=float).reindex(codes)
            daily = (px * qty).sum(axis=1) + state.bond_units * bond_idx.reindex(days) + state.cash
            drawdown = float(daily.iloc[-1] / daily.max() - 1) if daily.max() > 0 else 0.0
            # 경계값(정확히 80%)도 알림. 부동소수 오차(92/100−1 = −0.0799…) 때문에 아주 작은 여유를 둔다
            if limits.loss_limit is not None and drawdown <= limits.loss_limit * p.loss_alert_ratio + 1e-9:
                conditions.append({"type": "R5", "target": "",
                                   "detail": {"drawdown": _r(drawdown), "limit": limits.loss_limit}})

    # R6 연 1회 정기 점검 (새 재무가 반영되는 4월 첫 영업일 이후)
    april = april_first_trading_day(trading_days, t.year)
    if (april is not None and t >= april and state.purchased_at is not None
            and pd.Timestamp(state.purchased_at) < april
            and (state.last_r6_year is None or state.last_r6_year < t.year)):
        conditions.append({"type": "R6", "target": "", "detail": {"year": t.year}})

    # R7 보유 종목이 상장폐지 위험
    for c in codes:
        if delist.get(c):
            conditions.append({"type": "R7", "target": c, "detail": {"rules": list(delist[c])}})

    assets = [{"asset": c, "quantity": state.holdings[c],
               "close": None if pd.isna(close[c]) else float(close[c]),
               "value": round(stock_value[c]), "weight": _r(cur[c]), "weight_in_stock": _r(w_in[c]),
               "sector": sectors[c], "delist_rules": list(delist.get(c, []))} for c in codes]
    assets.sort(key=lambda a: (-a["value"], a["asset"]))
    assets += [{"asset": BOND, "units": state.bond_units, "value": round(value[BOND]), "weight": _r(cur[BOND])},
               {"asset": CASH, "value": round(value[CASH]), "weight": _r(cur[CASH])}]

    return {
        "as_of": t.date().isoformat(),
        "total_value": round(total),
        "bond_index": _r(bidx, 6),
        "assets": assets,
        "by_asset": {"stock": _r(stock_total / total if total > 0 else 0.0), "bond": _r(cur[BOND]), "cash": _r(cur[CASH])},
        "by_sector_in_stock": [{"sector": s, "weight": _r(w)} for s, w in sorted(by_sector.items(), key=lambda x: -x[1])],
        "drawdown": None if drawdown is None else _r(drawdown),
        "conditions": conditions,
    }
