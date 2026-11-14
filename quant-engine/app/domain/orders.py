"""정수 주식 수와 기존 보유 합산 (결정 8-6·4-3, docs/03 §1.8.4)."""
from __future__ import annotations

import math
from dataclasses import dataclass

import pandas as pd


@dataclass(frozen=True)
class OrderItem:
    code: str
    weight: float
    close: float
    amount: int        # 배정 금액
    shares: int
    spend: int         # 실제 매수 금액


def order_items(stock_amount: float, weights: pd.Series, close: pd.Series) -> list[OrderItem]:
    """T-ORD-01: 1,000,000 × [0.5, 0.5], 종가 [300,000, 70,000] → [1주, 7주], 남는 돈 210,000."""
    items = []
    for code, w in weights.items():
        amount = int(math.floor(stock_amount * w))
        price = float(close[code])
        shares = int(amount // price)
        items.append(OrderItem(code, float(w), price, amount, shares, int(round(shares * price))))
    return sorted(items, key=lambda i: (-i.weight, i.code))


@dataclass(frozen=True)
class Plan:
    stock_amount: int
    bond_amount: int
    bond_units: float
    cash_amount: int
    cash_from_rounding: int
    items: list[OrderItem]


def plan(invest: int, stock: float, bond: float, weights: pd.Series, close: pd.Series, bond_index: float) -> Plan:
    stock_amt = invest * stock
    items = order_items(stock_amt, weights, close)
    spent = sum(i.spend for i in items)
    bond_amt = int(math.floor(invest * bond))
    return Plan(
        stock_amount=spent,
        bond_amount=bond_amt,
        bond_units=bond_amt / bond_index if bond_index else 0.0,
        cash_amount=invest - spent - bond_amt,
        cash_from_rounding=int(math.floor(stock_amt)) - spent,
        items=items,
    )


@dataclass(frozen=True)
class Combined:
    values: dict[str, float]          # 종목코드·BOND·CASH → 평가금액
    total: float

    def weights(self) -> dict[str, float]:
        return {k: round(v / self.total, 4) for k, v in self.values.items()} if self.total else {}


def combine(existing_values: dict[str, float], existing_bond_value: float, existing_cash: float,
            p: Plan) -> Combined:
    values: dict[str, float] = dict(existing_values)
    for i in p.items:
        if i.shares:
            values[i.code] = values.get(i.code, 0.0) + i.spend
    values["BOND"] = existing_bond_value + p.bond_amount
    values["CASH"] = existing_cash + p.cash_amount
    values = {k: v for k, v in values.items() if v > 0}
    return Combined(values, sum(values.values()))
