"""백테스트 실험 A~D와 과거 성과 카드 (docs/03 §1.12, 01 FR-BT-*·FR-REC-14).

각 리밸런싱일 t에는 t까지의 데이터만 쓴다 (md.snapshot(t), NFR-03).
실험 D는 추천 계산(recommend.compute)을 그대로 쓰고, 아주 큰 금액을 넣어 정수 주식·최소 금액 규칙이 걸리지 않게 해
비중만 꺼낸다.
"""
from __future__ import annotations

import time
from collections.abc import Callable
from dataclasses import dataclass, field

import pandas as pd

from app.config import Settings
from app.data.market_data import MarketData
from app.domain import backtest_engine as bt
from app.domain import indicators, metrics, recommend, scoring, screening, weighting
from app.domain.types import FACTOR_ORDER, Edits, Existing, ProfileSettings
from app.services.compute_service import reco_params

TOP_N = 15
HUGE_AMOUNT = 10**12            # 실험 D: 정수 주식·종목당 최소 금액이 걸리지 않을 만큼 큰 금액
LIMITATIONS = ["SURVIVORSHIP", "NO_MANAGED_FILTER", "SYNTHETIC_BOND"]
EXPERIMENTS = {
    "A": [*FACTOR_ORDER, "MULTI_NEUTRAL", "MULTI_GLOBAL"],
    "B": ["MULTI_EW", "MULTI_IV", "MULTI_MV"],
    "C": ["MULTI_EW", "KOSPI"],
}


@dataclass(frozen=True)
class Profile:
    key: str
    settings: ProfileSettings


@dataclass(frozen=True)
class Params:
    start_year: int = 2015
    end_date: pd.Timestamp | None = None
    trading_cost_rate: float = 0.003
    experiments: tuple[str, ...] = ("A", "B", "C", "D")
    multi_weights: dict[str, float] = field(default_factory=lambda: {
        "VALUE": 0.30, "QUALITY": 0.30, "MOMENTUM": 0.25, "STABILITY": 0.15, "DIVIDEND": 0.0})
    profiles: tuple[Profile, ...] = ()


def inputs_at(md: MarketData, s: Settings, t: pd.Timestamp) -> recommend.Inputs:
    """리밸런싱일 t의 지표·공통 필터. 관리종목 목록은 2026년 한 시점뿐이라 백테스트에서는 쓰지 않는다 (FR-BT-03)."""
    def build() -> recommend.Inputs:
        snap = md.snapshot(t)
        ind = indicators.compute(snap, indicators.IndicatorParams(s.momentum_lookback, s.vol_lookback,
                                                                  s.vol_min_obs, s.rsi_period))
        common = screening.screen_common(ind, snap, screening.ScreenParams(
            screening.DelistParams(s.delist_mcap_min, s.delist_revenue_min, s.delist_price_min, s.delist_impairment),
            s.mcap_bottom_pct, use_managed=False))
        return recommend.Inputs(snap, ind, common, float(md.bond_idx.loc[snap.date]), md.data_version)

    return md.memo(("bt_inputs", t), build)


def _ew(codes: list[str]) -> pd.Series:
    return pd.Series(1 / len(codes), index=codes) if codes else pd.Series({bt.CASH: 1.0})


def _top(md: MarketData, inp: recommend.Inputs, weights: dict[str, float], neutral: bool, s: Settings) -> list[str]:
    """팩터 점수는 가중치와 무관하므로 날짜·업종중립 여부마다 한 번만 계산하고, 순위만 가중치로 다시 매긴다
    (scoring.score와 같은 순서: 점수 내림차순, 동점은 코드 순)."""
    scored = md.memo(("bt_scored", inp.snap.date, neutral), lambda: scoring.score(
        inp.ind.loc[inp.common.universe], inp.snap.stocks["sector_group"], {}, neutral=neutral,
        min_size=s.sector_min_size))
    total = sum(weights.get(f, 0.0) * scored[f"f_{f}"] for f in FACTOR_ORDER).sort_index()
    return list(total.sort_values(ascending=False, kind="mergesort").index[:TOP_N])


def profile_target(inp: recommend.Inputs, settings: ProfileSettings, s: Settings) -> tuple[pd.Series, pd.Series]:
    """실험 D: (서비스와 같은 목표 {종목: s·w, BOND, CASH}, 기준 {KOSPI: s, BOND, CASH})."""
    try:
        r = recommend.compute(inp, settings, HUGE_AMOUNT, Existing(), Edits(), reco_params(s))
    except recommend.NoCandidates:
        # shortcut: 후보가 하나도 없으면 전부 현금, 실제로 그런 해가 생기면 채권 배분까지 계산
        return pd.Series({bt.CASH: 1.0}), pd.Series({bt.CASH: 1.0})
    a = r["allocation"]
    items = r["methods"][r["method_selected"]]["items"]
    w = pd.Series({i["code"]: i["weight"] * a["stock"] for i in items}, dtype=float)
    target = pd.concat([w, pd.Series({bt.BOND: a["bond"], bt.CASH: a["cash"]})])
    bench = pd.Series({bt.KOSPI: a["stock"], bt.BOND: a["bond"], bt.CASH: a["cash"]})
    return target[target > 0], bench[bench > 0]


def run(md: MarketData, s: Settings, p: Params, progress: Callable[[float], None] = lambda _: None) -> dict:
    started = time.perf_counter()
    end = md.to_trading_day(p.end_date) if p.end_date is not None else md.as_of
    dates = bt.rebalance_dates(md.trading_days, p.start_year, end)
    if not dates:
        raise ValueError("리밸런싱일이 없어요 (시작 연도·끝 날짜 확인)")

    wanted = {k for e in p.experiments for k in EXPERIMENTS.get(e, [])}
    if "D" in p.experiments:
        wanted |= {pr.key for pr in p.profiles} | {f"BENCH_{pr.key}" for pr in p.profiles}
    targets: dict[str, dict[pd.Timestamp, pd.Series]] = {k: {} for k in wanted}

    for i, t in enumerate(dates):
        inp = inputs_at(md, s, t)
        multi = _top(md, inp, p.multi_weights, True, s)
        for f in FACTOR_ORDER:
            if f in wanted:
                targets[f][t] = _ew(_top(md, inp, {f: 1.0}, True, s))
        for k in ("MULTI_NEUTRAL", "MULTI_EW"):          # 같은 계산 (docs/03 §1.12)
            if k in wanted:
                targets[k][t] = _ew(multi)
        if "MULTI_GLOBAL" in wanted:
            targets["MULTI_GLOBAL"][t] = _ew(_top(md, inp, p.multi_weights, False, s))
        if {"MULTI_IV", "MULTI_MV"} & wanted:
            w = weighting.weights_all(multi, inp.ind["vol_1y"], inp.snap.returns_hist, 1.0,
                                      s.vol_lookback, s.cov_min_obs)   # 실험 B: 종목 한도 없음
            for k, m in (("MULTI_IV", "INVERSE_VOL"), ("MULTI_MV", "MIN_VARIANCE")):
                if k in wanted:
                    targets[k][t] = w.by_method[m] if multi else _ew([])
        if "KOSPI" in wanted:
            targets["KOSPI"][t] = pd.Series({bt.KOSPI: 1.0})
        if "D" in p.experiments:
            for pr in p.profiles:
                targets[pr.key][t], targets[f"BENCH_{pr.key}"][t] = profile_target(inp, pr.settings, s)
        progress((i + 1) / len(dates) * 0.9)

    r = bt.returns_matrix(md.returns, md.bond_ret, md.kospi_ret, dates[0], end)
    sectors = md.stocks["sector"].to_dict()
    by_key = {pr.key: pr.settings for pr in p.profiles}
    navs: dict[str, pd.Series] = {}
    strategies = []
    for k in sorted(wanted):
        prof = by_key.get(k)
        check = (bt.profile_band_check(sectors, prof.stock_limit, prof.sector_limit, s.rebalance_band)
                 if prof else None)
        nav = bt.run_nav(targets[k], r, p.trading_cost_rate, band_check=check)
        navs[k] = nav
        loss_limit = prof.loss_limit if prof else (by_key[k[6:]].loss_limit if k.startswith("BENCH_") else None)
        strategies.append({"strategy": k, "metrics": metrics.summary(nav, loss_limit),
                           "yearly": [{"year": y, "return": v} for y, v in metrics.yearly(nav).items()]})
    progress(1.0)
    experiments = {e: EXPERIMENTS[e] for e in p.experiments if e in EXPERIMENTS}
    if "D" in p.experiments:
        experiments["D"] = [x for pr in p.profiles for x in (pr.key, f"BENCH_{pr.key}")]
    return {
        "rebalance_dates": [d.date().isoformat() for d in dates],
        "experiments": experiments,
        "strategies": strategies,
        "limitations": LIMITATIONS,
        "elapsed_ms": round((time.perf_counter() - started) * 1000),
        "navs": navs,                                                     # 저장용 (응답에서는 뺀다)
        "holdings": {k: {d: v.round(6) for d, v in ts.items()} for k, ts in targets.items()},
    }


def history_card(md: MarketData, s: Settings, settings: ProfileSettings, start_year: int = 2015) -> dict:
    """과거 성과 카드 (FR-REC-14): 이 설정으로 start_year부터 실험 D 로직을 운용한 결과."""
    res = run(md, s, Params(start_year=start_year, experiments=("D",), profiles=(Profile("ME", settings),)))
    nav = res["navs"]["ME"]
    years = metrics.yearly(nav)
    worst = min(years, key=years.get)
    return {"start": nav.index[0].date().isoformat(), "end": nav.index[-1].date().isoformat(),
            "cumulative_return": round(float(nav.iloc[-1] / nav.iloc[0] - 1), 4),
            "max_drawdown": round(metrics.max_drawdown(nav), 4),
            "worst_year": {"year": worst, "return": round(years[worst], 4)},
            "data_version": md.data_version}
