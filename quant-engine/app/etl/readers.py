"""원본 파일 읽기 (docs/01 §2). 파일 형식에 관한 지식은 여기에만 둔다."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import pandas as pd

KOSPI_COLUMN = "KOSPI"


def normalize_code(code: object) -> str:
    """숫자만이면 6자리 0채움(5930 → 005930), 아니면 그대로(0126Z0)."""
    s = str(code).strip()
    if s.endswith(".0") and s[:-2].isdigit():      # 엑셀이 숫자로 읽은 경우
        s = s[:-2]
    return s.zfill(6) if s.isdigit() else s


def _to_float(df: pd.DataFrame) -> pd.DataFrame:
    """'38,700' 같은 문자열을 숫자로. 빈 칸은 NaN."""
    return df.apply(lambda c: pd.to_numeric(c.astype(str).str.replace(",", "", regex=False).str.strip()
                                            .replace({"nan": None, "": None}), errors="coerce"))


@dataclass(frozen=True)
class Sample:
    code_by_name: dict[str, str]     # 종목명 → 종목코드 (같은 이름이 두 번 나오면 첫 번째)
    prices: pd.DataFrame             # index=날짜(주말 포함), columns=종목명


def read_sample(path: Path) -> Sample:
    """KOSPI_PRICE_SAMPLE: cp949, 1행 종목코드, 2행 종목명, 그 아래 날짜별 종가 (DR-02)."""
    raw = pd.read_csv(path, header=None, encoding="cp949", low_memory=False, dtype=str)
    codes = [normalize_code(c) for c in raw.iloc[0, 1:]]
    names = [str(n).strip() for n in raw.iloc[1, 1:]]
    code_by_name: dict[str, str] = {}
    seen_codes: set[str] = set()
    for name, code in zip(names, codes):
        if name in code_by_name or code in seen_codes:
            continue
        code_by_name[name] = code
        seen_codes.add(code)

    body = raw.iloc[2:, 1:].copy()
    body.columns = names
    body.index = pd.to_datetime(raw.iloc[2:, 0])
    body.index.name = "date"
    body = body.loc[:, ~body.columns.duplicated()]
    return Sample(code_by_name, _to_float(body))


def read_long(path: Path) -> pd.DataFrame:
    """K_PRICE_LONG: 종목명 열 + KOSPI 열, 날짜 열 없음 (DR-01). 같은 종목 두 번째 열('.1')은 버린다."""
    df = pd.read_csv(path, encoding="utf-8-sig", low_memory=False)
    df = df.loc[:, [c for c in df.columns if not str(c).endswith(".1")]]
    return _to_float(df)


FINANCIAL_COLUMNS = {
    "종목코드": "code", "결산월": "settle_month", "회계연도": "fiscal_year",
    "자산총계": "total_assets", "부채총계": "total_liabilities", "자본총계": "total_equity",
    "자본금": "capital", "이익잉여금": "retained_earnings", "매출액": "revenue",
    "영업이익": "operating_income", "당기순이익": "net_income", "배당금": "dividends",
    "EPS": "eps", "BPS": "bps", "SPS": "sps", "CEPS": "ceps",
}


def read_financial(path: Path) -> pd.DataFrame:
    """재무 CSV (DR-03). 금액은 천원, 주당값은 원. 종목코드 앞 'A'를 뗀다."""
    df = pd.read_csv(path, encoding="utf-8-sig", dtype={"종목코드": str})
    df = df.rename(columns=FINANCIAL_COLUMNS)[list(FINANCIAL_COLUMNS.values())]
    df["code"] = df["code"].str.lstrip("A").map(normalize_code)
    return df


def read_sector(path: Path) -> pd.DataFrame:
    """업종분류 (DR-04): code, name, market, sector, market_cap."""
    df = pd.read_excel(path, dtype={"종목코드": str})
    df = df.rename(columns={"종목코드": "code", "종목명": "name", "시장구분": "market",
                            "업종명": "sector", "시가총액": "market_cap"})
    df["code"] = df["code"].map(normalize_code)
    return df[["code", "name", "market", "sector", "market_cap"]]


def read_managed(path: Path) -> pd.DataFrame:
    """관리종목 (DR-05). 종목코드가 정수로 읽히므로 6자리로, 지정일자 '2026/09/08' → 날짜."""
    df = pd.read_excel(path)
    return pd.DataFrame({
        "code": df["종목코드"].map(normalize_code),
        "managed_date": pd.to_datetime(df["지정일자"], format="%Y/%m/%d").dt.date,
    })


def read_ktb3y(path: Path) -> pd.Series:
    """KOSPI_MACRO의 국고채 3년 금리(%) (DR-06). index=날짜."""
    df = pd.read_excel(path, usecols=["date", "ktb_3y"])
    s = df.set_index(pd.to_datetime(df["date"]).dt.normalize())["ktb_3y"].astype(float)
    return s[~s.index.duplicated(keep="last")].sort_index()
