"""포트폴리오 평가(I8)·리밸런싱 수량(I9) 서비스. 최신 영업일 기준으로 평가하고 알림 조건을 판정한다."""
from __future__ import annotations

import pandas as pd

from app.config import Settings
from app.data.market_data import MarketData
from app.domain import alerts, rebalance
from app.domain.recommend import UnknownStocks
from app.domain.types import Edits, Existing, ProfileSettings
from app.services import compute_service, stock_service


def alert_params(s: Settings) -> alerts.AlertParams:
    return alerts.AlertParams(rebalance_band=s.rebalance_band, loss_alert_ratio=s.loss_alert_ratio)


def evaluate(md: MarketData, s: Settings, state: alerts.PortfolioState, limits: alerts.Limits) -> dict:
    unknown = sorted(c for c in state.holdings if c not in md.stocks.index)
    if unknown:
        raise UnknownStocks(unknown)
    snap = md.snapshot()
    delist = {i.code: i.delist_rules for i in stock_service.latest_items(md, s) if i.delist_rules}
    sectors = md.stocks["sector"].to_dict()
    result = alerts.evaluate(state, limits, snap.date, snap.close_hist, md.bond_idx.loc[:snap.date],
                             sectors, delist, md.trading_days, alert_params(s))
    return {**result, "data_version": md.data_version}


def rebalance_plan(md: MarketData, s: Settings, state: alerts.PortfolioState, limits: alerts.Limits,
                   profile: ProfileSettings, alert_type: str, alert_target: str) -> dict:
    """I9. 알림 종류에 맞는 목표 비중으로 사고팔 수량 (docs/03 §1.11)."""
    unknown = sorted(c for c in state.holdings if c not in md.stocks.index)
    if unknown:
        raise UnknownStocks(unknown)
    snap = md.snapshot()
    bidx = float(md.bond_idx.loc[snap.date])
    held_close = alerts.last_closes(snap.close_hist, sorted(state.holdings))
    stock_value = {c: q * float(held_close[c]) for c, q in state.holdings.items() if pd.notna(held_close[c])}
    total = sum(stock_value.values()) + state.bond_units * bidx + state.cash
    if total <= 0:
        raise ValueError("평가금액이 0이라 리밸런싱할 수 없어요")
    current = {**{c: v / total for c, v in stock_value.items()},
               rebalance.BOND: state.bond_units * bidx / total, rebalance.CASH: state.cash / total}

    if alert_type == "R6":
        # 같은 프로필로 지금 총가치를 처음부터 다시 추천받은 결과 (순위에서 밀린 종목 교체는 R6에서만, 결정 4-8a)
        reco = compute_service.compute(md, s, profile, round(total), Existing(), Edits())
        target = dict(reco["combined"]["target_weights"])
    else:
        target = rebalance.target_weights(alert_type, alert_target, state.targets, current,
                                          md.stocks["sector"].to_dict(), limits.stock_limit, limits.sector_limit)
    codes = sorted(set(state.holdings) | {k for k in target if k not in (rebalance.BOND, rebalance.CASH)})
    close = alerts.last_closes(snap.close_hist, codes)
    result = rebalance.plan(target, state.holdings, close, state.bond_units, bidx, state.cash)
    return {**result, "as_of": snap.date.date().isoformat(), "data_version": md.data_version,
            "bond_index": round(bidx, 6), "alert_type": alert_type, "alert_target": alert_target}
