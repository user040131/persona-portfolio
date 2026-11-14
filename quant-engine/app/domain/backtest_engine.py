"""백테스트 NAV 계산 (docs/03 §1.12). 목표 비중을 받아 날마다 굴린다. 목표를 정하는 일은 서비스가 한다."""
from __future__ import annotations

from collections.abc import Callable

import numpy as np
import pandas as pd

BOND, CASH, KOSPI = "BOND", "CASH", "KOSPI"


def rebalance_dates(trading_days: pd.DatetimeIndex, start_year: int, end: pd.Timestamp) -> list[pd.Timestamp]:
    """매년 4월 1일 이후 첫 영업일 (새 재무가 반영되는 날, FR-BT-02)."""
    out = []
    for y in range(start_year, end.year + 1):
        i = trading_days.searchsorted(pd.Timestamp(y, 4, 1))
        if i < len(trading_days) and trading_days[i] <= end:
            out.append(trading_days[i])
    return out


def month_ends(days: pd.DatetimeIndex) -> set[pd.Timestamp]:
    """매달 마지막 영업일. 데이터 마지막 날은 그 달이 끝났는지 모르므로 뺀다."""
    s = pd.Series(days, index=days)
    return set(s.groupby([days.year, days.month]).max()) - {days[-1]}


def returns_matrix(stock_returns: pd.DataFrame, bond_ret: pd.Series, kospi_ret: pd.Series,
                   start: pd.Timestamp, end: pd.Timestamp) -> pd.DataFrame:
    """결측 수익률은 0 (거래정지·상장폐지 후 값이 그대로 남는다 → 생존 편향 한계, FR-BT-09)."""
    r = pd.concat([stock_returns, bond_ret.rename(BOND), kospi_ret.rename(KOSPI)], axis=1).loc[start:end]
    r[CASH] = 0.0
    return r.fillna(0.0)


def rebalance(v: np.ndarray, w: np.ndarray, cost: float) -> np.ndarray:
    """거래 금액 × 비용을 빼고 목표 비중으로 (v·w는 같은 자산 순서)."""
    total = v.sum()
    fee = np.abs(w * total - v).sum() * cost
    return w * (total - fee)


def run_nav(targets: dict[pd.Timestamp, pd.Series], r: pd.DataFrame, cost: float, init: float = 100.0,
            band_check: Callable[[pd.Series, pd.Series], bool] | None = None) -> pd.Series:
    """첫날은 비용 없이 목표대로 산다. 리밸런싱일엔 새 목표로, 월말엔 band_check가 참이면 같은 목표로 되돌린다.
    날마다 도는 부분은 속도 때문에 numpy 배열로 계산한다 (자산 = 모든 목표에 나온 자산의 합집합)."""
    days = r.index
    cols = sorted(set().union(*(t.index for t in targets.values())))
    rr = r.reindex(columns=cols, fill_value=0.0).to_numpy()
    tw = {d: t.reindex(cols, fill_value=0.0).to_numpy() for d, t in targets.items()}
    ends = month_ends(days) if band_check else set()
    w = tw[days[0]]
    v = w * init
    nav = np.empty(len(days))
    nav[0] = v.sum()
    for i in range(1, len(days)):
        v = v * (1 + rr[i])
        d = days[i]
        if d in tw:
            w = tw[d]
            v = rebalance(v, w, cost)
        elif d in ends and band_check(pd.Series(v / v.sum(), cols), pd.Series(w, cols)):
            v = rebalance(v, w, cost)
        nav[i] = v.sum()
    return pd.Series(nav, index=days)


def profile_band_check(sectors: dict[str, str], stock_limit: float, sector_limit: float,
                       band: float) -> Callable[[pd.Series, pd.Series], bool]:
    """실험 D 월말 점검 = 서비스 알림 R1~R4 (docs/03 §1.10).
    R1·R2는 목표 자체가 한도를 넘는 경우(종목 수가 적어 1/N > 한도) 매달 거래하지 않도록 목표보다 커졌을 때만 본다."""
    def stock_part(x: pd.Series) -> pd.Series:
        s = x.drop([BOND, CASH, KOSPI], errors="ignore")
        return s / s.sum() if s.sum() > 0 else s

    def by_sector(x: pd.Series) -> pd.Series:
        return x.groupby(x.index.map(lambda c: sectors.get(c, c))).sum()

    def check(cur: pd.Series, w: pd.Series) -> bool:
        ci, wi = stock_part(cur), stock_part(w).reindex(stock_part(cur).index, fill_value=0.0)
        if ((ci > stock_limit) & (ci > wi + 1e-9)).any():
            return True
        cs, ws = by_sector(ci), by_sector(wi).reindex(by_sector(ci).index, fill_value=0.0)
        if ((cs > sector_limit) & (cs > ws + 1e-9)).any():
            return True
        idx = cur.index.union(w.index)
        c, t = cur.reindex(idx, fill_value=0.0), w.reindex(idx, fill_value=0.0)
        if ((c - t).abs() > band).any():
            return True
        safe = lambda x: x.get(BOND, 0.0) + x.get(CASH, 0.0)  # noqa: E731
        return safe(c) < safe(t) - band

    return check
