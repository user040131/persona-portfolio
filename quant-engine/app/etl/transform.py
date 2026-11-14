"""원본 → market 스키마 행 (docs/03 §1.13). 순수 함수만 둔다(DB 모름)."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date

import pandas as pd

from app.etl.readers import KOSPI_COLUMN
from app.etl.validators import EtlValidationError

UNCLASSIFIED = "미분류"


def restore_dates(long_df: pd.DataFrame, sessions_until_last: pd.DatetimeIndex) -> pd.DataFrame:
    """날짜 열이 없는 장기 가격에 영업일을 붙인다. 마지막 행 = 마지막 영업일, 끝에서부터 거꾸로 (FR-ETL-02)."""
    n = len(long_df)
    if len(sessions_until_last) < n:
        raise EtlValidationError(f"영업일이 {len(sessions_until_last)}개뿐이라 {n}행에 날짜를 붙일 수 없어요")
    out = long_df.copy()
    out.index = sessions_until_last[-n:]
    out.index.name = "date"
    return out


def only_sessions(prices: pd.DataFrame, sessions: pd.DatetimeIndex) -> pd.DataFrame:
    """샘플 가격의 주말·휴일 행을 뺀다 (강의 `주가데이터_비영업일처리`)."""
    return prices.loc[prices.index.normalize().isin(sessions)]


@dataclass(frozen=True)
class PriceRows:
    prices: pd.DataFrame          # code, date, close
    kospi: pd.DataFrame           # index_code, date, close
    unmapped_names: list[str]     # 샘플에 코드가 없어 버린 종목명


def price_rows(long_dated: pd.DataFrame, code_by_name: dict[str, str]) -> PriceRows:
    stocks = long_dated.drop(columns=[KOSPI_COLUMN], errors="ignore")
    unmapped = [c for c in stocks.columns if c not in code_by_name]
    stocks = stocks.drop(columns=unmapped).rename(columns=code_by_name)
    long = (stocks.rename_axis(index="date", columns="code").stack().rename("close").reset_index())
    long = long.dropna(subset=["close"])
    long["date"] = long["date"].dt.date
    kospi = pd.DataFrame({"index_code": KOSPI_COLUMN, "date": long_dated.index.date,
                          "close": long_dated[KOSPI_COLUMN].to_numpy()}).dropna(subset=["close"])
    return PriceRows(long[["code", "date", "close"]], kospi, unmapped)


def apply_date(fiscal_year: int, month: int = 4, day: int = 1) -> date:
    """재무 적용일 = 회계연도 다음 해 4월 1일 (강의 `종목선정_포트폴리오`)."""
    return date(int(fiscal_year) + 1, month, day)


def financial_rows(fin: pd.DataFrame, known_codes: set[str], month: int = 4, day: int = 1) -> tuple[pd.DataFrame, int]:
    """가격이 있는 종목만 남기고 적용일·출처를 붙인다. (행, 버린 행 수)"""
    keep = fin["code"].isin(known_codes)
    out = fin.loc[keep].copy()
    out["apply_date"] = [apply_date(y, month, day) for y in out["fiscal_year"]]
    out["source"] = "FILE"
    return out, int((~keep).sum())


def sector_group_of(sector: str, groups: dict[str, list[str]]) -> str:
    for group, members in groups.items():
        if sector in members:
            return group
    return sector


def stock_rows(code_by_name: dict[str, str], prices: pd.DataFrame, sector: pd.DataFrame,
               managed: pd.DataFrame, groups: dict[str, list[str]]) -> pd.DataFrame:
    """가격이 있는 종목 기준으로 업종·관리종목을 붙인다. 업종이 없으면 '미분류' (FR-ETL-07)."""
    first_seen = prices.groupby("code")["date"].min()
    sector_by_code = sector.drop_duplicates("code").set_index("code")["sector"]
    managed_by_code = managed.drop_duplicates("code").set_index("code")["managed_date"]
    rows = []
    for name, code in code_by_name.items():
        if code not in first_seen.index:
            continue
        sec = sector_by_code.get(code)
        sec = sec if isinstance(sec, str) and sec else UNCLASSIFIED
        md = managed_by_code.get(code)
        rows.append({
            "code": code, "name": name, "market": "KOSPI", "sector": sec,
            "sector_group": sector_group_of(sec, groups),
            "is_managed": md is not None, "managed_date": md, "first_seen": first_seen[code],
        })
    return pd.DataFrame(rows).sort_values("code").reset_index(drop=True)
