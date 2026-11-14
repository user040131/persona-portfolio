"""테스트용 합성 데이터."""
from __future__ import annotations

from datetime import date

import numpy as np
import pandas as pd

from app.domain.types import Snapshot


def tiny_tables(close: float = 247500.0) -> dict[str, pd.DataFrame]:
    """load_base가 만드는 테이블과 같은 모양의 아주 작은 데이터 (2영업일, 1종목)."""
    d = [date(2026, 8, 18), date(2026, 8, 19)]
    return {
        "trading_day": pd.DataFrame({"date": d}),
        "stock": pd.DataFrame([{"code": "005930", "name": "삼성전자", "market": "KOSPI", "sector": "전기·전자",
                                "sector_group": "전기·전자", "is_managed": False, "managed_date": None,
                                "first_seen": d[0]}]),
        "price_daily": pd.DataFrame({"code": ["005930", "005930"], "date": d, "close": [268500.0, close]}),
        "index_daily": pd.DataFrame({"index_code": "KOSPI", "date": d, "close": [6869.83, 6471.17]}),
        "rate_daily": pd.DataFrame({"series": "KTB3Y", "date": d, "value": [3.0, 3.1]}),
        "bond_index_daily": pd.DataFrame({"date": d, "ret": [0.0, -0.0027], "idx": [100.0, 99.73]}),
        "financial_annual": pd.DataFrame([{"code": "005930", "fiscal_year": 2025, "settle_month": 12,
                                           "apply_date": date(2026, 4, 1), "eps": 6564.0, "bps": 63997.0,
                                           "total_assets": None, "source": "FILE"}]),
    }


# 금액은 천원. 자본총계 7천억 원 ÷ BPS 1만 원 → 추정 주식 수 7천만 주
FIN_DEFAULT = dict(total_assets=1e9, total_liabilities=3e8, total_equity=7e8, capital=5e7, revenue=5e8,
                   operating_income=8e7, net_income=6e7, dividends=-1e7,
                   eps=1_000.0, bps=10_000.0, sps=5_000.0, ceps=1_500.0)


def snapshot(closes: dict[str, list[float]], fin: dict[str, dict] | None = None,
             stocks: dict[str, dict] | None = None) -> Snapshot:
    """종목별 종가 목록(같은 길이)으로 스냅샷을 만든다. 날짜는 끝이 2026-08-19인 영업일 비슷한 일련번호."""
    n = len(next(iter(closes.values())))
    idx = pd.bdate_range(end="2026-08-19", periods=n)
    close = pd.DataFrame(closes, index=idx).sort_index(axis=1)
    fin_rows = {c: {**FIN_DEFAULT, **(fin or {}).get(c, {})} for c in close.columns}
    st = {c: {"name": c, "sector": "전기·전자", "sector_group": "전기·전자", "is_managed": False,
              **(stocks or {}).get(c, {})} for c in close.columns}
    return Snapshot(
        date=idx[-1], close_hist=close, returns_hist=close.pct_change(fill_method=None),
        kospi_ret_hist=pd.Series(0.0, index=idx), bond_ret_hist=pd.Series(0.0, index=idx),
        fin=pd.DataFrame.from_dict(fin_rows, orient="index"), stocks=pd.DataFrame.from_dict(st, orient="index"),
    )


def random_walk(n: int, start: float = 10_000.0, seed: int = 0, drift: float = 0.0005) -> list[float]:
    rng = np.random.default_rng(seed)
    return list(start * np.cumprod(1 + rng.normal(drift, 0.02, n)))
