"""추천 계산 파이프라인 (docs/03 §1.5~1.9, 내부 API I6).

지표 → 공통 필터 → 업종 중립 점수 → 성향 필터 → 종목 선택 → 비중 3방식 → 주식·채권·현금 배분
→ 정수 주식 수 → 기존 보유와 합산 → 근거 사실·탈락 사유.
결과는 I6 응답 본문 그대로(snake_case dict)다. Spring이 그대로 버전으로 저장한다.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd

from app.domain import allocation, facts, scoring, screening, selection, weighting
from app.domain.orders import combine, plan
from app.domain.types import Edits, Existing, ProfileSettings, RecoParams, Snapshot

USER_REMOVED = "USER_REMOVED"
RANK_OUT = "RANK_OUT"


class NoCandidates(Exception):
    """성향 필터까지 거치고 남은 종목이 없음 → 409 INSUFFICIENT_UNIVERSE."""


class UnknownStocks(Exception):
    def __init__(self, codes: list[str]):
        super().__init__(f"unknown stock codes: {codes}")
        self.codes = codes


@dataclass(frozen=True)
class Inputs:
    snap: Snapshot
    ind: pd.DataFrame                     # 지표 (가격 있는 모든 종목)
    common: screening.ScreenResult        # 공통 필터 결과
    bond_index: float                     # 기준일 채권 지수
    data_version: int


def _r(x: float, d: int = 4) -> float:
    return round(float(x), d)


def compute(inp: Inputs, settings: ProfileSettings, invest_amount: int, existing: Existing,
            edits: Edits = Edits(), p: RecoParams = RecoParams()) -> dict:
    snap, ind = inp.snap, inp.ind
    close = ind["close"]
    sector = snap.stocks["sector"]

    unknown = [c for c in existing.holdings if c not in ind.index or pd.isna(close.get(c))]
    if unknown:
        raise UnknownStocks(sorted(unknown))

    # ---- 점수 (업종 중립) → 성향 필터 ----
    universe = inp.common.universe
    scored = scoring.score(ind.loc[universe], snap.stocks["sector_group"], settings.factor_weights,
                           neutral=True, min_size=p.sector_min_size)
    prof = screening.screen_profile(scored, ind, sector, vol_exclude_top_pct=settings.vol_exclude_top_pct,
                                    exclude_overheat=settings.exclude_overheat,
                                    excluded_sectors=tuple(settings.excluded_sectors), rsi_overheat=p.rsi_overheat)
    removed = set(edits.removed_codes)
    ranked = [c for c in prof.passed if c not in removed]
    if not ranked:
        raise NoCandidates("no candidates after profile filters")

    # ---- 기존 보유 ----
    ex_values = {c: float(q * close[c]) for c, q in existing.holdings.items()}
    ex_sectors: dict[str, float] = {}
    for c, v in ex_values.items():
        ex_sectors[sector[c]] = ex_sectors.get(sector[c], 0.0) + v
    ex_bond = existing.bond_units * inp.bond_index
    method = edits.method or settings.default_method

    # ---- 종목 선택 ↔ 주식 몫 맞추기 (최대 3회, 결정 10-8) ----
    def pick(budget: float) -> selection.Selection:
        return selection.select(ranked, close, sector, settings.n_stocks, budget, ex_values, ex_sectors,
                                settings.sector_limit, p.min_amount_per_stock)

    def weigh(sel_: selection.Selection) -> weighting.Weights:
        return weighting.weights_all(sel_.picks, ind["vol_1y"], snap.returns_hist, settings.stock_limit,
                                     p.vol_lookback, p.cov_min_obs)

    def mix_for(w_: weighting.Weights, has_stocks: bool) -> allocation.Mix:
        m = allocation.allocate(w_.by_method[method], snap, settings.min_cash,
                                settings.stock_cap if has_stocks else 0.0, settings.loss_limit,
                                p.mdd_lookback_years, p.mdd_min_years, p.loss_limit_margin, p.bond_shock_loss)
        if not has_stocks:
            # 담을 종목이 없으면 주식 0% (빈 바구니를 '위험 없는 주식'으로 보면 안 된다).
            # 주식 몫이 종목당 최소 금액도 안 될 만큼 작아진 원인은 손실 한도다
            m = allocation.Mix(0.0, m.bond, allocation.LOSS_LIMIT if settings.loss_limit is not None
                               else allocation.CAP, m.expected_mdd)
        return m

    budget = invest_amount * settings.stock_cap
    for _ in range(p.max_rounds):
        sel = pick(budget)
        w = weigh(sel)
        mix = mix_for(w, bool(sel.picks))
        if not sel.picks or pick(invest_amount * mix.stock).picks == sel.picks:
            break                                   # 주식 몫이 바뀌어도 고를 종목이 같으면 수렴
        budget = invest_amount * mix.stock
    else:
        # 수렴하지 않으면 마지막 주식 몫으로 고른 종목을 쓰고, 그 비중으로 예상 하락폭만 다시 잰다
        sel = pick(invest_amount * mix.stock)
        w = weigh(sel)
        if not sel.picks:
            mix = mix_for(w, False)
        else:
            start = allocation.window_start(snap, p.mdd_lookback_years, p.mdd_min_years)
            r = mix.stock * allocation.basket_returns(w.by_method[method], snap, start) \
                + mix.bond * allocation.bond_returns(snap, start)
            mix = allocation.Mix(mix.stock, mix.bond, mix.binding, allocation.mdd(r))

    warnings: list[dict] = []
    if edits.safe_ratio is not None:
        # 사용자가 정한 주식 몫으로 종목을 다시 고른 뒤(1주 가격·최소 금액 규칙), 그 비중으로 예상 하락폭 계산
        user_stock = round(1.0 - min(max(edits.safe_ratio, settings.min_cash), 1.0), 2)
        sel = selection.select(ranked, close, sector, settings.n_stocks, invest_amount * user_stock, ex_values,
                               ex_sectors, settings.sector_limit, p.min_amount_per_stock)
        w = weighting.weights_all(sel.picks, ind["vol_1y"], snap.returns_hist, settings.stock_limit,
                                  p.vol_lookback, p.cov_min_obs)
        mix = allocation.user_mix(mix, edits.safe_ratio, settings.min_cash, w.by_method[method], snap,
                                  p.mdd_lookback_years, p.mdd_min_years)
        if settings.loss_limit is not None and mix.expected_mdd < settings.loss_limit:
            warnings.append({"type": "LOSS_LIMIT_EXCEEDED",
                             "detail": {"expected_mdd": _r(mix.expected_mdd), "loss_limit": settings.loss_limit}})
    if mix.stock == 0:
        sel = selection.Selection([], {}, 0, 0.0)
        warnings.append({"type": "NO_STOCK_ALLOCATION", "detail": {}})
    if w.min_variance_fallback and sel.picks:
        warnings.append({"type": "MIN_VARIANCE_FALLBACK", "detail": {}})
    if sel.picks and sel.n_target < settings.n_stocks:
        warnings.append({"type": "N_REDUCED", "detail": {"n_requested": settings.n_stocks, "n_final": len(sel.picks)}})

    # ---- 주문 (세 방식 모두 같은 주식·채권·현금 비율로) ----
    plans = {m: plan(invest_amount, mix.stock, mix.bond, w.by_method[m] if sel.picks else pd.Series(dtype=float),
                     close, inp.bond_index) for m in w.by_method}
    chosen = plans[method]

    # ---- 기존 보유와 합산 ----
    comb = combine(ex_values, ex_bond, existing.cash, chosen)
    tw = comb.weights()
    stock_codes = [k for k in tw if k not in ("BOND", "CASH")]
    by_asset = {"stock": _r(sum(tw[k] for k in stock_codes)), "bond": tw.get("BOND", 0.0), "cash": tw.get("CASH", 0.0)}
    by_sector: dict[str, float] = {}
    for k in stock_codes:
        by_sector[sector[k]] = by_sector.get(sector[k], 0.0) + comb.values[k] / comb.total
    start = allocation.window_start(snap, p.mdd_lookback_years, p.mdd_min_years)
    if stock_codes:
        stock_w = pd.Series({k: comb.values[k] for k in stock_codes})
        r_comb = by_asset["stock"] * allocation.basket_returns(stock_w / stock_w.sum(), snap, start)
    else:
        r_comb = np.zeros(len(snap.bond_ret_hist.loc[start:]))
    r_comb = r_comb + by_asset["bond"] * allocation.bond_returns(snap, start)
    combined_mdd = allocation.mdd(r_comb)
    if existing.holdings and settings.loss_limit is not None and combined_mdd < settings.loss_limit:
        warnings.append({"type": "COMBINED_LOSS_LIMIT",
                         "detail": {"expected_mdd": _r(combined_mdd), "loss_limit": settings.loss_limit}})

    # ---- 근거 사실 ----
    held_codes = set(existing.holdings)
    held_sectors = {sector[c] for c in held_codes}
    stocks_out = []
    for code in sel.picks:
        row = scored.loc[code]
        stocks_out.append({
            "code": code, "name": snap.stocks.at[code, "name"], "sector": sector[code],
            "group": str(row["group"]), "group_n": int(row["group_n"]), "rank_in_group": int(row["rank_in_group"]),
            "total_score": _r(row["total"]),
            "factor_scores": {f: _r(row[f"f_{f}"]) for f in settings.factor_weights},
            "indicators": {k: (None if pd.isna(ind.at[code, k]) else _r(ind.at[code, k], 6))
                           for k in ("per", "pbr", "psr", "pcr", "roe", "roa", "momentum_6m", "vol_1y",
                                     "rsi_14", "debt_ratio", "div_yield")},
            "is_held": code in held_codes,
            "facts": facts.stock_facts(code, scored, ind, settings.factor_weights, prof.vol_pct_desc, sector[code],
                                       held_codes, held_sectors, high_vol_pct=p.high_vol_caution_pct,
                                       rsi_overheat=p.rsi_overheat, weak_score=p.weak_factor_score),
        })

    screening_map = _screening_map(ind, inp.common, scored, prof, removed, sel, settings.sector_limit)
    skipped_counts: dict[str, int] = {}
    for v in screening_map.values():
        if v["stage"] in ("PROFILE", "SELECTION") and v["reason"] != RANK_OUT:
            skipped_counts[v["reason"]] = skipped_counts.get(v["reason"], 0) + 1

    return {
        "as_of": snap.date.date().isoformat(),
        "data_version": inp.data_version,
        "bond_index": _r(inp.bond_index, 6),
        "allocation": {
            "stock": mix.stock, "bond": mix.bond, "cash": mix.cash,
            "stock_amount": chosen.stock_amount, "bond_amount": chosen.bond_amount,
            "bond_units": _r(chosen.bond_units, 6), "cash_amount": chosen.cash_amount,
            "cash_from_rounding": chosen.cash_from_rounding,
            "lookback": {"start": start.date().isoformat(), "end": snap.date.date().isoformat()},
            "expected_mdd": _r(mix.expected_mdd),
        },
        "method_selected": method,
        "methods": {m: {"fallback": m == "MIN_VARIANCE" and w.min_variance_fallback,
                        "items": [{"code": i.code, "weight": i.weight, "close": i.close, "amount": i.amount,
                                   "shares": i.shares, "spend": i.spend} for i in pl.items]}
                    for m, pl in plans.items()},
        "stocks": stocks_out,
        "portfolio_facts": {
            "stock_binding": mix.binding, "expected_mdd": _r(mix.expected_mdd),
            "lookback_years": round((snap.date - start).days / 365.25, 1),
            "stock_cap": settings.stock_cap, "min_cash": settings.min_cash, "loss_limit": settings.loss_limit,
            "n_requested": settings.n_stocks, "n_final": len(sel.picks),
            "n_reduced": bool(sel.picks) and sel.n_target < settings.n_stocks,
            "skipped": dict(sorted(skipped_counts.items())), "cash_from_rounding": chosen.cash_from_rounding,
            "method_selected": method,
        },
        "screening": screening_map,
        "combined": {
            "target_weights": tw, "by_asset": by_asset,
            "by_sector": [{"sector": s, "weight": _r(v)} for s, v in sorted(by_sector.items(), key=lambda kv: -kv[1])],
            "expected_mdd": _r(combined_mdd),
        },
        "warnings": warnings,
    }


def _screening_map(ind: pd.DataFrame, common: screening.ScreenResult, scored: pd.DataFrame,
                   prof: screening.ProfileScreen, removed: set[str], sel: selection.Selection,
                   sector_limit: float) -> dict:
    """'이 종목은 왜 없나요?'용 (docs/03 §1.9). 가격이 있든 없든 지표 행이 있는 모든 종목."""
    out: dict[str, dict] = {}
    picked = set(sel.picks)
    for code in ind.index:
        if code in common.reasons.index:
            reason = common.reasons[code]
            params = {"rules": list(common.delist.get(code, []))} if reason == screening.DELIST_RISK else {}
            out[code] = {"stage": "COMMON", "reason": reason, "params": params}
        elif code in prof.reasons.index:
            out[code] = {"stage": "PROFILE", "reason": prof.reasons[code], "params": prof.params.get(code, {})}
        elif code in picked:
            out[code] = {"stage": "PICKED", "reason": None, "params": {}}
        elif code in removed:
            out[code] = {"stage": "SELECTION", "reason": USER_REMOVED, "params": {}}
        elif code in sel.skipped:
            reason = sel.skipped[code]
            params = ({"close": float(ind.at[code, "close"])} if reason == selection.PRICE_TOO_HIGH
                      else {"limit": sector_limit})
            out[code] = {"stage": "SELECTION", "reason": reason, "params": params}
        else:
            row = scored.loc[code]
            out[code] = {"stage": "SELECTION", "reason": RANK_OUT,
                         "params": {"group": str(row["group"]), "group_n": int(row["group_n"]),
                                    "rank_in_group": int(row["rank_in_group"])}}
    return out
