"""주식 안 비중: 동일가중·변동성 역수·최소분산 (계획서 p5, 결정 8-4, docs/03 §1.8.2).

최소분산은 강의 `asset_allocation_26.py`의 MVP와 같은 SLSQP 설정에 종목당 상한을 더했다.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd
from scipy.optimize import minimize

from app.domain.types import METHODS


def cap_weights(w: pd.Series, cap: float) -> pd.Series:
    """상한을 넘는 만큼 잘라 나머지 종목에 비례해 나눈다 (T-W-01: [0.5,0.3,0.2], 0.4 → [0.4,0.36,0.24])."""
    w = w.astype(float).copy()
    for _ in range(len(w)):
        over = w > cap + 1e-12
        if not over.any():
            break
        excess = float((w[over] - cap).sum())
        w[over] = cap
        free = ~over & (w < cap - 1e-12)
        if not free.any():
            break
        w[free] += excess * w[free] / w[free].sum()
    return w


def round_weights(w: pd.Series, digits: int = 4) -> pd.Series:
    """소수 4자리로 반올림하고 합이 정확히 1이 되도록 가장 큰 비중에서 보정한다."""
    r = w.round(digits)
    if len(r):
        r[r.idxmax()] = round(r[r.idxmax()] + (1.0 - r.sum()), digits)
    return r


@dataclass(frozen=True)
class Weights:
    by_method: dict[str, pd.Series]
    min_variance_fallback: bool
    cov_annual: np.ndarray | None      # 최소분산에 쓴 공분산 (예상 변동성 계산용)


def solve_min_variance(cov: np.ndarray, cap: float = 1.0) -> np.ndarray | None:
    n = cov.shape[0]
    res = minimize(lambda x: float(np.sqrt(x @ cov @ x)), np.full(n, 1 / n), method="SLSQP",
                   bounds=[(0.0, cap)] * n,
                   constraints=({"type": "eq", "fun": lambda x: x.sum() - 1.0},),
                   options={"ftol": 1e-12, "maxiter": 1000})
    if not res.success:
        return None
    x = np.clip(res.x, 0, None)
    return x / x.sum()


def weights_all(codes: list[str], vol: pd.Series, returns_hist: pd.DataFrame, stock_limit: float,
                vol_lookback: int = 252, cov_min_obs: int = 120) -> Weights:
    if not codes:
        return Weights({m: pd.Series(dtype=float) for m in METHODS}, False, None)
    cap = max(stock_limit, 1 / len(codes))           # 한도가 1/N보다 작으면 1/N으로 (결정 8-4)
    ew = cap_weights(pd.Series(1 / len(codes), index=codes), cap)
    inv = 1 / vol[codes]
    iv = cap_weights(inv / inv.sum(), cap)

    r = returns_hist.reindex(columns=codes).iloc[-vol_lookback:].dropna(how="any")
    cov = None
    mv, fallback = iv, True
    if len(r) >= cov_min_obs:
        cov = r.cov().to_numpy() * 252
        x = solve_min_variance(cov, cap)
        if x is not None:
            mv, fallback = pd.Series(x, index=codes), False
    return Weights({"EQUAL": round_weights(ew), "INVERSE_VOL": round_weights(iv),
                    "MIN_VARIANCE": round_weights(mv)}, fallback, cov)
