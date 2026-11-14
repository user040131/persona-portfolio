"""성과 지표 (docs/03 §1.12, 강의 ret_risk_profile)."""
from __future__ import annotations

import math

import pandas as pd


def drawdown(nav: pd.Series) -> pd.Series:
    return nav / nav.cummax() - 1


def max_drawdown(nav: pd.Series) -> float:
    return float(drawdown(nav).min())


def cagr(nav: pd.Series) -> float:
    years = (nav.index[-1] - nav.index[0]).days / 365.25
    cum = nav.iloc[-1] / nav.iloc[0]
    return float(cum ** (1 / years) - 1) if years > 0 else 0.0


def yearly(nav: pd.Series) -> dict[int, float]:
    """연말 nav 변화율. 첫 해는 첫날 대비."""
    ends = nav.groupby(nav.index.year).last()
    prev = pd.concat([pd.Series([nav.iloc[0]], index=[ends.index[0] - 1]), ends.iloc[:-1]])
    return {int(y): float(ends[y] / prev.iloc[i] - 1) for i, y in enumerate(ends.index)}


def breaches(nav: pd.Series, loss_limit: float) -> tuple[int, int]:
    """(한도 아래였던 날 수, 연속 구간 수)."""
    below = drawdown(nav) < loss_limit
    return int(below.sum()), int((below & ~below.shift(1, fill_value=False)).sum())


def summary(nav: pd.Series, loss_limit: float | None = None) -> dict[str, float]:
    weekly = nav.resample("W").last().dropna().pct_change(fill_method=None).dropna()
    vol = float(weekly.std() * math.sqrt(52)) if len(weekly) > 1 else 0.0
    down = weekly[weekly < 0]
    dvol = float(down.std() * math.sqrt(52)) if len(down) > 1 else 0.0
    g = cagr(nav)
    out = {
        "cumulative_return": float(nav.iloc[-1] / nav.iloc[0] - 1),
        "cagr": g,
        "volatility": vol,
        "sharpe": g / vol if vol > 0 else 0.0,
        "downside_volatility": dvol,
        "sortino": g / dvol if dvol > 0 else 0.0,
        "max_drawdown": max_drawdown(nav),
    }
    if loss_limit is not None:
        out["limit_breach_days"], out["limit_breach_episodes"] = breaches(nav, loss_limit)
    return out
