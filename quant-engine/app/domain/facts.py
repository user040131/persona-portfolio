"""근거 '사실' (docs/03 §1.9). 코드와 숫자만 만든다. 한국어 문장은 Spring 템플릿이 만든다 (결정 11-2)."""
from __future__ import annotations

import pandas as pd

from app.domain.scoring import REPRESENTATIVE
from app.domain.types import FACTOR_ORDER


def _num(x: float | None, digits: int = 6) -> float | None:
    return None if x is None or pd.isna(x) else round(float(x), digits)


def stock_facts(code: str, scored: pd.DataFrame, ind: pd.DataFrame, weights: dict[str, float],
                vol_pct_desc: pd.Series, sector: str, held_codes: set[str], held_sectors: set[str], *,
                high_vol_pct: float = 0.20, rsi_overheat: float = 0.70, weak_score: float = 0.30) -> dict:
    row = scored.loc[code]
    active = [f for f in FACTOR_ORDER if weights.get(f, 0) > 0]
    top2 = sorted(active, key=lambda f: (-row[f"f_{f}"], FACTOR_ORDER.index(f)))[:2]
    link = max(active, key=lambda f: (weights[f], -FACTOR_ORDER.index(f))) if active else None

    cautions = []
    if vol_pct_desc.get(code, 1.0) <= high_vol_pct:
        cautions.append({"code": "HIGH_VOL", "vol_1y": _num(ind.at[code, "vol_1y"], 4)})
    if ind.at[code, "rsi_14"] >= rsi_overheat:
        cautions.append({"code": "OVERHEAT", "rsi_14": _num(ind.at[code, "rsi_14"], 4)})
    for f in active:
        if row[f"f_{f}"] < weak_score:
            cautions.append({"code": "WEAK_FACTOR", "factor": f})

    if code in held_codes:
        role = {"code": "HELD", "sector": sector}
    elif not held_codes:
        role = {"code": "NO_HOLDINGS", "sector": sector}
    elif sector not in held_sectors:
        role = {"code": "DIVERSIFY", "sector": sector}
    else:
        role = {"code": "OVERLAP", "sector": sector}

    profile_link = None
    if link:
        col = REPRESENTATIVE[link]
        profile_link = {"factor": link, "pct_top": _num(1 - row[f"s_{col}"], 4), "value": _num(ind.at[code, col], 6)}

    return {
        "selection": {"top_factors": top2, "group": str(row["group"]), "group_n": int(row["group_n"]),
                      "rank_in_group": int(row["rank_in_group"])},
        "level": {"per": _num(ind.at[code, "per"], 2), "pbr": _num(ind.at[code, "pbr"], 2),
                  "roe": _num(ind.at[code, "roe"], 4)},
        "profile_link": profile_link,
        "cautions": cautions,
        "role": role,
    }
