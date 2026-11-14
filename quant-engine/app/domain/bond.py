"""국고채 3년 금리 → 채권 수익률·지수 (결정 1-6, docs/03 §1.3).

하루 수익률 = 전날 금리 ÷ 252 (이자) − 듀레이션 × 금리 변화 (가격 변화)
"""
from __future__ import annotations

import pandas as pd


def bond_returns(rate_pct: pd.Series, duration: float = 2.8) -> pd.Series:
    """rate_pct: 영업일 index, 금리(%), 결측 없이 채운 상태."""
    y = rate_pct.astype(float) / 100.0
    r = y.shift(1) / 252.0 - duration * (y - y.shift(1))
    return r.fillna(0.0)


def bond_index(rate_pct: pd.Series, duration: float = 2.8, base: float = 100.0) -> pd.Series:
    """첫날을 base로 하는 누적 지수."""
    return base * (1.0 + bond_returns(rate_pct, duration)).cumprod()


def to_trading_days(rate_pct: pd.Series, trading_days: pd.DatetimeIndex) -> pd.Series:
    """영업일에 맞춘다. 금리가 없는 영업일은 직전 값으로 채운다."""
    idx = rate_pct.index.union(trading_days)
    return rate_pct.reindex(idx).ffill().reindex(trading_days)
