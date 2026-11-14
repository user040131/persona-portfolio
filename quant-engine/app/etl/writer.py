"""market 테이블 교체 적재. 한 트랜잭션으로 비우고 다시 넣는다 (FR-ETL-09 멱등).

백테스트 결과·캐시 테이블은 건드리지 않는다.
"""
from __future__ import annotations

import time
from collections.abc import Mapping

import numpy as np
import pandas as pd
from sqlalchemy import Engine, delete, insert

from app.db.models import Base

CHUNK = 20_000

# 적재 순서(외래키는 없지만 읽기 쉽게 기준 → 시계열 순)
REPLACE_ORDER = ("trading_day", "stock", "price_daily", "index_daily", "rate_daily", "bond_index_daily",
                 "financial_annual", "price_flag")


def _records(df: pd.DataFrame) -> list[dict]:
    """NaN → None, numpy 스칼라 → 파이썬 값."""
    clean = df.astype(object).where(pd.notna(df), None)
    out = clean.to_dict(orient="records")
    for row in out:
        for k, v in row.items():
            if isinstance(v, np.generic):
                row[k] = v.item()
    return out


def replace_tables(engine: Engine, frames: Mapping[str, pd.DataFrame]) -> list[dict]:
    """frames에 있는 테이블만 비우고 넣는다. 단계 기록(테이블, 행 수, ms)을 돌려준다."""
    steps: list[dict] = []
    tables = Base.metadata.tables
    with engine.begin() as conn:
        for name in REPLACE_ORDER:
            if name not in frames:
                continue
            started = time.perf_counter()
            table = tables[name]
            conn.execute(delete(table))
            df = frames[name]
            for i in range(0, len(df), CHUNK):
                conn.execute(insert(table), _records(df.iloc[i:i + CHUNK]))
            steps.append({"table": name, "rows": len(df), "elapsed_ms": int((time.perf_counter() - started) * 1000)})
    return steps
