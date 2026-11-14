"""market 스키마가 실제 MySQL 8.4에서 만들어지는지 (docs/03 §4.1). Docker가 없으면 건너뛴다."""
from __future__ import annotations

import pytest
from sqlalchemy import inspect

from app.db.engine import init_schema, make_engine

pytestmark = pytest.mark.mysql

EXPECTED = {
    "etl_log", "trading_day", "stock", "price_daily", "price_flag", "index_daily", "rate_daily",
    "bond_index_daily", "financial_annual", "backtest_run", "backtest_nav", "backtest_metric",
    "backtest_yearly", "backtest_holding", "profile_backtest_cache",
}


def test_market_테이블이_모두_만들어진다(mysql_url: str) -> None:
    engine = make_engine(mysql_url)
    init_schema(engine)
    init_schema(engine)   # 두 번 불러도 문제없어야 한다

    insp = inspect(engine)
    assert EXPECTED <= set(insp.get_table_names())
    cols = {c["name"]: c for c in insp.get_columns("etl_log")}
    assert getattr(cols["started_at"]["type"], "fsp", None) == 3   # DATETIME(3)
    assert {i["name"] for i in insp.get_indexes("price_daily")} >= {"ix_price_date"}
    engine.dispose()
