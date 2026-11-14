"""주식·채권·현금 비율 (결정 8-5·8-5a, docs/03 §1.8.3).

과거 7년(데이터가 짧으면 최소 5년)을 매일 같은 비율로 맞춘다고 보고 시뮬레이션해서,
손실 한도 안에서 주식을 최대로, 그다음 채권을 최대로 담고 나머지는 현금으로 둔다.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd

from app.domain.types import Snapshot

CAP = "CAP"
LOSS_LIMIT = "LOSS_LIMIT"
ALL_CASH = "ALL_CASH"
USER = "USER"


class InsufficientHistory(Exception):
    """시뮬레이션에 필요한 최소 기간(5년)이 안 됨."""


def mdd(r: np.ndarray) -> float:
    """최대 하락폭. 시작값 1도 고점 후보에 넣는다(첫날 하락도 하락이다)."""
    if len(r) == 0:
        return 0.0
    v = np.cumprod(1.0 + np.asarray(r, dtype=float))
    peak = np.maximum(np.maximum.accumulate(v), 1.0)
    return float(min((v / peak - 1.0).min(), 0.0))


def _mdd_rows(paths: np.ndarray) -> np.ndarray:
    """행마다 수익률 경로의 최대 하락폭 (시작값 1 포함)."""
    v = np.cumprod(1.0 + paths, axis=1)
    peak = np.maximum(np.maximum.accumulate(v, axis=1), 1.0)
    return np.minimum((v / peak - 1.0).min(axis=1), 0.0)


@dataclass(frozen=True)
class Mix:
    stock: float
    bond: float
    binding: str          # CAP | LOSS_LIMIT | ALL_CASH | USER
    expected_mdd: float

    @property
    def cash(self) -> float:
        return abs(round(1.0 - self.stock - self.bond, 2))   # -0.0 방지


def choose_mix(r_stock: np.ndarray, r_bond: np.ndarray, min_cash: float, stock_cap: float,
               loss_limit: float | None, bond_shock_loss: float = 0.0) -> Mix:
    """1%p 단위 격자에서 고른다. T-ALC-01~03의 기준 함수.
    bond_shock_loss: 금리 충격 때 채권 손실률. 채권 비중 × 이 값도 한도 안이어야 한다 (과거에 없던 금리 급등 대비)."""
    cmin = int(round(min_cash * 100))
    top = max(0, min(int(round(stock_cap * 100)), 100 - cmin))
    if loss_limit is None:
        s, b = top, 100 - cmin - top
        return Mix(s / 100, b / 100, CAP, mdd(s / 100 * r_stock + b / 100 * r_bond))
    for s in range(top, -1, -1):
        bs = np.arange(100 - cmin - s, -1, -1) / 100            # 채권 큰 것부터
        dd = _mdd_rows(s / 100 * r_stock[None, :] + bs[:, None] * r_bond[None, :])
        ok = np.nonzero((dd >= loss_limit - 1e-12) & (-bs * bond_shock_loss >= loss_limit - 1e-12))[0]
        if len(ok):
            i = ok[0]
            return Mix(s / 100, float(bs[i]), CAP if s == top else LOSS_LIMIT, float(dd[i]))
    return Mix(0.0, 0.0, ALL_CASH, 0.0)


def window_start(snap: Snapshot, lookback_years: int = 7, min_years: int = 5) -> pd.Timestamp:
    first = snap.close_hist.index[0]
    start = max(snap.date - pd.DateOffset(years=lookback_years), first)
    if (snap.date - start).days < 365 * min_years:
        raise InsufficientHistory(f"{snap.date.date()} 기준 과거 데이터가 {min_years}년이 안 돼요")
    return start


def basket_returns(weights: pd.Series, snap: Snapshot, start: pd.Timestamp) -> np.ndarray:
    """정해진 비중의 주식 바구니 일간 수익률. 아직 상장 전인 종목은 그날 비중에서 빼고, 아무것도 없으면 KOSPI로."""
    kospi = snap.kospi_ret_hist.loc[start:]
    if weights.empty:
        return np.zeros(len(kospi))
    r = snap.returns_hist.loc[start:, weights.index]
    avail = r.notna()
    num = (r.fillna(0.0) * weights).sum(axis=1)
    den = (avail * weights).sum(axis=1)
    out = (num / den).where(den > 0, kospi)
    return out.fillna(0.0).to_numpy()


def bond_returns(snap: Snapshot, start: pd.Timestamp) -> np.ndarray:
    return snap.bond_ret_hist.loc[start:].fillna(0.0).to_numpy()


def allocate(weights: pd.Series, snap: Snapshot, min_cash: float, stock_cap: float, loss_limit: float | None,
             lookback_years: int = 7, min_years: int = 5, margin: float = 1.0, bond_shock_loss: float = 0.0) -> Mix:
    """margin: 한도에 곱하는 여유 (0.7이면 -10% 한도를 -7%로 보고 고른다)."""
    start = window_start(snap, lookback_years, min_years)
    target = None if loss_limit is None else loss_limit * margin
    return choose_mix(basket_returns(weights, snap, start), bond_returns(snap, start), min_cash, stock_cap, target,
                      bond_shock_loss)


def user_mix(auto: Mix, safe_ratio: float, min_cash: float, weights: pd.Series, snap: Snapshot,
             lookback_years: int = 7, min_years: int = 5) -> Mix:
    """사용자가 안전자산 비중을 정한 경우 (결정 8-7). 채권:현금은 자동 결과 비율을 따르되 현금 ≥ 최소 현금."""
    safe = round(min(max(safe_ratio, min_cash), 1.0), 2)
    auto_safe = auto.bond + auto.cash
    bond = round(safe * auto.bond / auto_safe, 2) if auto_safe > 0 else 0.0
    if safe - bond < min_cash:
        bond = round(max(safe - min_cash, 0.0), 2)
    stock = round(1.0 - safe, 2)
    start = window_start(snap, lookback_years, min_years)
    expected = mdd(stock * basket_returns(weights, snap, start) + bond * bond_returns(snap, start))
    return Mix(stock, bond, USER, expected)
