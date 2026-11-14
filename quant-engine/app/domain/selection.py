"""종목 고르기 (결정 8-2a·8-3, docs/03 §1.8.1).

점수 순서대로 담되, 1주 가격이 배정 금액보다 크거나 기존 보유와 합쳐 업종 한도를 넘으면 건너뛴다.
"""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass

import pandas as pd

PRICE_TOO_HIGH = "PRICE_TOO_HIGH"
SECTOR_LIMIT = "SECTOR_LIMIT"


@dataclass(frozen=True)
class Selection:
    picks: list[str]
    skipped: dict[str, str]          # 코드 → 사유 (건너뛴 것만, 순위 밖은 따로)
    n_target: int                    # 최소 금액 규칙으로 줄인 뒤의 목표 종목 수
    per_stock: float                 # 종목당 배정 금액(균등 가정)


def select(ranked: list[str], close: pd.Series, sector: pd.Series, n_stocks: int, stock_budget: float,
           existing_values: dict[str, float], existing_sectors: dict[str, float], sector_limit: float,
           min_amount_per_stock: int = 500_000) -> Selection:
    n = min(n_stocks, int(stock_budget // min_amount_per_stock))   # 종목당 최소 50만 원
    if n <= 0:
        return Selection([], {}, 0, 0.0)
    per = stock_budget / n
    pool = sum(existing_values.values()) + stock_budget
    sec_val: dict[str, float] = defaultdict(float, existing_sectors)
    picks: list[str] = []
    skipped: dict[str, str] = {}
    for code in ranked:
        if len(picks) == n:
            break
        if close[code] > per:
            skipped[code] = PRICE_TOO_HIGH
            continue
        sec = sector[code]
        if (sec_val[sec] + per) / pool > sector_limit + 1e-12:
            skipped[code] = SECTOR_LIMIT
            continue
        picks.append(code)
        sec_val[sec] += per
    return Selection(picks, skipped, n, per)
