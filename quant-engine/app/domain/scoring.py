"""업종 중립 멀티팩터 점수 (결정 5-3·6-1~6-4, docs/03 §1.7).

지표 점수 = 1 − (그룹 안 순위 ÷ 그룹 크기), 팩터 점수 = 지표 점수 평균, 종합 = Σ 가중치 × 팩터 점수.
후보가 min_size 미만인 그룹은 유니버스 전체와 비교한다(__ALL__).
"""
from __future__ import annotations

import pandas as pd

from app.domain.types import FACTOR_ORDER

ALL = "__ALL__"

# 팩터 → [(지표, 높을수록 좋은가)]
INDICATORS: dict[str, list[tuple[str, bool]]] = {
    "VALUE": [("per", False), ("pbr", False), ("psr", False), ("pcr", False)],
    "QUALITY": [("roe", True), ("roa", True)],
    "MOMENTUM": [("momentum_6m", True)],
    "STABILITY": [("debt_ratio", False)],
    "DIVIDEND": [("div_yield", True)],
}

# 근거 문구에서 팩터를 대표하는 지표 (docs/03 §1.9 profile_link)
REPRESENTATIVE = {"VALUE": "per", "QUALITY": "roe", "MOMENTUM": "momentum_6m",
                  "STABILITY": "debt_ratio", "DIVIDEND": "div_yield"}


def score(u: pd.DataFrame, groups: pd.Series, weights: dict[str, float],
          neutral: bool = True, min_size: int = 10) -> pd.DataFrame:
    """u: 유니버스 지표(index=code). 점수 높은 순으로 정렬해 돌려준다(동점은 코드 순)."""
    u = u.sort_index()
    g = groups.reindex(u.index).fillna(ALL).astype(object)
    if neutral:
        sizes = g.map(g.value_counts())
        g[sizes < min_size] = ALL
    else:
        g[:] = ALL

    out = pd.DataFrame(index=u.index)
    members = {name: idx for name, idx in g.groupby(g).groups.items()}
    for factor, cols in INDICATORS.items():
        parts = []
        for col, higher_better in cols:
            s = pd.Series(index=u.index, dtype=float)
            for name, idx in members.items():
                pool = u.index if name == ALL else idx       # __ALL__은 유니버스 전체와 비교
                rk = u.loc[pool, col].rank(ascending=not higher_better, method="average")
                s.loc[idx] = 1 - rk.loc[idx] / len(pool)
            out[f"s_{col}"] = s
            parts.append(s)
        out[f"f_{factor}"] = pd.concat(parts, axis=1).mean(axis=1)

    out["total"] = sum(weights.get(f, 0.0) * out[f"f_{f}"] for f in FACTOR_ORDER)
    out["group"] = g
    out["group_n"] = [len(u) if name == ALL else len(members[name]) for name in g]
    out = out.sort_values("total", ascending=False, kind="mergesort")   # 안정 정렬 → 동점은 코드 순 유지
    out["rank_in_group"] = out.groupby("group").cumcount() + 1
    return out
