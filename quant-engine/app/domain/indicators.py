"""종목 지표 (docs/03 §1.5). 재무 금액은 천원, 주당값은 원.

입력은 Snapshot(기준일까지의 데이터)뿐이다. 출력은 index=종목코드(오름차순) DataFrame.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd

from app.domain.types import Snapshot

FIN_FIELDS = ["total_assets", "total_liabilities", "total_equity", "capital", "revenue", "operating_income",
              "net_income", "dividends", "eps", "bps", "sps", "ceps"]

COLUMNS = ["close", "shares_est", "mcap", "per", "pbr", "psr", "pcr", "roe", "roa", "momentum_6m",
           "vol_1y", "rsi_14", "debt_ratio", "div_yield", "revenue_won"]


@dataclass(frozen=True)
class IndicatorParams:
    momentum_lookback: int = 120
    vol_lookback: int = 252
    vol_min_obs: int = 200
    rsi_period: int = 14


def _ratio(num: pd.Series, den: pd.Series) -> pd.Series:
    """분모가 0 이하이거나 없으면 NaN."""
    return (num / den).where(den > 0)


def rsi(close_hist: pd.DataFrame, period: int = 14) -> pd.Series:
    """강의 `종목선정_포트폴리오` RSI와 같은 값(0~1). rs/(1+rs) = gain/(gain+loss)."""
    w = close_hist.iloc[-(period + 1):]
    d = w.diff().iloc[1:]
    gain = d.where(d > 0, 0.0).mean()
    loss = (-d.where(d < 0, 0.0)).mean()
    with np.errstate(divide="ignore", invalid="ignore"):
        out = gain / (gain + loss)
    out[(gain == 0) & (loss == 0)] = 0.5
    out[d.isna().any() | (len(d) < period)] = np.nan
    return out


def compute(snap: Snapshot, p: IndicatorParams = IndicatorParams()) -> pd.DataFrame:
    codes = snap.close_hist.columns
    fin = snap.fin.reindex(index=codes, columns=FIN_FIELDS).apply(pd.to_numeric, errors="coerce")
    close = snap.close_hist.iloc[-1]

    shares = _ratio(fin["total_equity"] * 1000, fin["bps"])
    mcap = close * shares

    profitable = (fin["net_income"] > 0) & (fin["operating_income"] > 0)
    roe = _ratio(fin["net_income"], fin["total_equity"]).where(profitable)
    roa = _ratio(fin["net_income"], fin["total_assets"]).where(profitable)

    hist = snap.close_hist
    if len(hist) > p.momentum_lookback:
        momentum = hist.iloc[-1] / hist.iloc[-1 - p.momentum_lookback] - 1
    else:
        momentum = pd.Series(np.nan, index=codes)

    rets = snap.returns_hist.iloc[-p.vol_lookback:]
    vol = (rets.std(ddof=1) * np.sqrt(252)).where(rets.count() >= p.vol_min_obs)
    # 1년 내내 가격이 그대로면(거래정지 등) 변동성 0 → 비교할 가격 자료가 없는 것으로 본다.
    # 0을 그대로 두면 변동성 역수 비중이 1/0이 된다 (2018·2022·2026년 4월에 실제로 있음)
    vol = vol.where(vol > 0)

    dividends = fin["dividends"].fillna(0.0).abs()
    out = pd.DataFrame({
        "close": close,
        "shares_est": shares,
        "mcap": mcap,
        "per": _ratio(close, fin["eps"]),
        "pbr": _ratio(close, fin["bps"]),
        "psr": _ratio(close, fin["sps"]),
        "pcr": _ratio(close, fin["ceps"]),
        "roe": roe,
        "roa": roa,
        "momentum_6m": momentum,
        "vol_1y": vol,
        "rsi_14": rsi(hist, p.rsi_period),
        "debt_ratio": _ratio(fin["total_liabilities"], fin["total_equity"]),
        "div_yield": (dividends * 1000 / mcap).where(mcap > 0),
        "revenue_won": fin["revenue"] * 1000,
    }, index=codes)
    out.index.name = "code"
    return out[COLUMNS].sort_index()
