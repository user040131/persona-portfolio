"""리밸런싱 수량 (docs/03 §1.11, 01 FR-ALT-09, 결정 4-8a·10-13).

목표 비중을 정하고(알림 종류마다 다름), 지금 총가치로 목표 금액을 만든 뒤 1주 단위로 내림해 사고팔 수량을 낸다.
채권은 금액 그대로 맞추고, 남는 돈(1주 단위로 맞추고 남은 돈 포함)은 현금이다. 거래 비용은 넣지 않는다.
"""
from __future__ import annotations

import math

import pandas as pd

BOND, CASH = "BOND", "CASH"


def _stocks(w: dict[str, float]) -> dict[str, float]:
    return {k: v for k, v in w.items() if k not in (BOND, CASH)}


def _cut(w: dict[str, float], members: list[str], keep_total: float,
         no_receive: frozenset[str] = frozenset()) -> dict[str, float]:
    """members의 합을 keep_total로 줄이고, 줄인 만큼을 나머지 주식에 비중대로 나눈다 (결정 10-13: 채권으로 옮기지 않음).
    no_receive(직접 추가한 종목)에는 더 넣지 않는다. 나눌 주식이 없으면 현금으로 둔다."""
    w = dict(w)
    now = sum(w.get(m, 0.0) for m in members)
    if now <= keep_total + 1e-12:
        return w
    scale = keep_total / now if now > 0 else 0.0
    for m in members:
        w[m] = w.get(m, 0.0) * scale
    excess = now - keep_total
    others = {k: v for k, v in _stocks(w).items() if k not in members and k not in no_receive and v > 0}
    total_others = sum(others.values())
    if total_others > 0:
        for k, v in others.items():
            w[k] = v + excess * v / total_others
    else:
        w[CASH] = w.get(CASH, 0.0) + excess
    return w


def target_weights(alert_type: str, alert_target: str, targets: dict[str, float], current: dict[str, float],
                   sectors: dict[str, str], stock_limit: float, sector_limit: float) -> dict[str, float]:
    """R3·R4·R5: 저장된 목표 그대로. R1·R2·R7: 목표에서 해당 종목·업종을 한도까지(R7은 0) 깎는다.
    R1·R2·R7에서는 목표에 없는데 갖고 있는 종목(직접 추가한 종목)은 지금 비중을 유지한다."""
    base = dict(targets) if targets else dict(current)
    if alert_type in ("R3", "R4", "R5"):
        return base

    extra = {k: v for k, v in _stocks(current).items() if k not in base and v > 0}
    if extra:
        keep = 1.0 - sum(extra.values())
        base = {k: v * keep for k, v in base.items()}
        base.update(extra)

    s_total = sum(_stocks(base).values())
    added = frozenset(extra)
    if alert_type == "R1":
        return _cut(base, [alert_target], stock_limit * s_total, added)
    if alert_type == "R7":
        return _cut(base, [alert_target], 0.0, added)
    if alert_type == "R2":
        members = [c for c in _stocks(base) if sectors.get(c) == alert_target]
        return _cut(base, members, sector_limit * s_total, added)
    raise ValueError(f"목표 비중을 정할 수 없는 알림: {alert_type}")


def plan(target: dict[str, float], holdings: dict[str, int], close: pd.Series, bond_units: float,
         bond_index: float, cash: int) -> dict:
    """목표 비중 → 사고팔 수량. close는 보유·목표 종목의 최신 종가."""
    stock_value = {c: q * float(close[c]) for c, q in holdings.items() if pd.notna(close.get(c))}
    total = sum(stock_value.values()) + bond_units * bond_index + cash

    trades = []
    after_qty: dict[str, int] = {}
    for c in sorted(set(holdings) | set(_stocks(target))):
        q = holdings.get(c, 0)
        px = close.get(c)
        if px is None or pd.isna(px) or px <= 0:
            after_qty[c] = q            # 가격이 없으면 건드리지 않는다
            continue
        tq = math.floor(target.get(c, 0.0) * total / float(px) + 1e-9)
        after_qty[c] = tq
        if tq != q:
            d = tq - q
            trades.append({"asset": c, "side": "BUY" if d > 0 else "SELL", "quantity": abs(d),
                           "close": float(px), "amount": round(abs(d) * float(px))})

    bond_after = target.get(BOND, 0.0) * total
    bond_now = bond_units * bond_index
    if abs(bond_after - bond_now) >= 1:
        trades.append({"asset": BOND, "side": "BUY" if bond_after > bond_now else "SELL",
                       "amount": round(abs(bond_after - bond_now)),
                       "units": round(abs(bond_after - bond_now) / bond_index, 6)})
    trades.sort(key=lambda t: (t["side"] != "SELL", -t["amount"], t["asset"]))

    stocks_after = {c: q * float(close[c]) for c, q in after_qty.items() if q > 0 and pd.notna(close.get(c))}
    stock_after = sum(stocks_after.values())
    cash_after = total - stock_after - bond_after
    w = (lambda v: round(v / total, 4) if total > 0 else 0.0)
    return {
        "total_value": round(total),
        "target_weights": {k: round(v, 4) for k, v in sorted(target.items(), key=lambda x: -x[1]) if v > 0},
        "trades": trades,
        "before": {"by_asset": {"stock": w(sum(stock_value.values())), "bond": w(bond_now), "cash": w(cash)}},
        "after": {
            "quantities": {c: q for c, q in after_qty.items() if q > 0},
            "bond_units": round(bond_after / bond_index, 6) if bond_index > 0 else 0.0,
            "cash": round(cash_after),
            "by_asset": {"stock": w(stock_after), "bond": w(bond_after), "cash": w(cash_after)},
            "weights": {**{c: w(v) for c, v in stocks_after.items()}, BOND: w(bond_after), CASH: w(cash_after)},
        },
    }
