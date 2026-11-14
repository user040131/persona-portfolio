"""적재 전 검증 (01 FR-ETL-03)."""
from __future__ import annotations

from dataclasses import dataclass

import pandas as pd


class EtlValidationError(RuntimeError):
    """원본 데이터가 기대와 다름. 적재를 중단한다."""


@dataclass(frozen=True)
class RestorationCheck:
    overlap_days: int
    compared_cells: int
    mismatches: int
    examples: list[dict]


def check_restoration(long_prices: pd.DataFrame, sample_prices: pd.DataFrame, tolerance: float = 0.5) -> RestorationCheck:
    """날짜를 붙인 장기 가격과 샘플 가격을 겹치는 영업일·종목에서 비교한다.

    둘 다 값이 있는 칸 중 |차이| > tolerance 인 칸이 하나라도 있으면 날짜 복원이 틀린 것이다.
    """
    days = long_prices.index.intersection(sample_prices.index)
    names = long_prices.columns.intersection(sample_prices.columns)
    a = long_prices.loc[days, names]
    b = sample_prices.loc[days, names]
    both = a.notna() & b.notna()
    bad = both & ((a - b).abs() > tolerance)
    examples = []
    if bad.values.any():
        stacked = bad.stack()
        for (day, name) in stacked[stacked].index[:5]:
            examples.append({"date": str(day.date()), "name": name,
                             "long": float(a.at[day, name]), "sample": float(b.at[day, name])})
    return RestorationCheck(len(days), int(both.values.sum()), int(bad.values.sum()), examples)


def require_restoration_ok(check: RestorationCheck) -> None:
    if check.overlap_days == 0 or check.compared_cells == 0:
        raise EtlValidationError("장기 가격과 샘플 가격이 겹치는 날이 없어요. 날짜 복원을 확인하세요.")
    if check.mismatches:
        raise EtlValidationError(
            f"날짜 복원 검증 실패: {check.compared_cells}칸 중 {check.mismatches}칸 불일치. 예: {check.examples}")
