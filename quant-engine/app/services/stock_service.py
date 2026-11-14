"""종목 목록·상세·업종 (docs/03 §3 I3·I4·I5). 최신 영업일 기준 판정은 데이터 버전마다 한 번만 계산한다."""
from __future__ import annotations

from dataclasses import dataclass

import pandas as pd

from app.config import Settings
from app.data.market_data import MarketData
from app.domain import indicators, screening


@dataclass(frozen=True)
class StockItem:
    code: str
    name: str
    sector: str
    sector_group: str
    is_managed: bool
    last_close: float | None
    delist_rules: list[str]


def _params(settings: Settings) -> tuple[indicators.IndicatorParams, screening.DelistParams]:
    return (
        indicators.IndicatorParams(settings.momentum_lookback, settings.vol_lookback,
                                   settings.vol_min_obs, settings.rsi_period),
        screening.DelistParams(settings.delist_mcap_min, settings.delist_revenue_min,
                               settings.delist_price_min, settings.delist_impairment),
    )


def latest_items(md: MarketData, settings: Settings) -> list[StockItem]:
    def build() -> list[StockItem]:
        ind_p, delist_p = _params(settings)
        snap = md.snapshot()
        ind = indicators.compute(snap, ind_p)
        rules = screening.delist_rules(ind, snap, delist_p, use_managed=True)
        items = []
        for code, row in md.stocks.iterrows():
            close = ind["close"].get(code)
            items.append(StockItem(
                code=code, name=row["name"], sector=row["sector"], sector_group=row["sector_group"],
                is_managed=bool(row["is_managed"]),
                last_close=None if close is None or pd.isna(close) else float(close),
                delist_rules=list(rules.get(code, [])),
            ))
        return items

    return md.memo(("latest_items",), build)


def sectors(md: MarketData) -> list[dict]:
    counts = md.stocks.groupby(["sector", "sector_group"]).size().reset_index(name="stock_count")
    return counts.sort_values("sector").to_dict(orient="records")
