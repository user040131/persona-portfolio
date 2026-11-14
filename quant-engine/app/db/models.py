"""market 스키마 (docs/03 §4.1). 이 스키마는 엔진만 쓴다 (docs/02 §6).

MySQL 8.4가 기준이고, 단위 테스트를 위해 SQLite에서도 만들어지도록 공통 타입을 쓰되
MySQL에서는 DATETIME(3)·DOUBLE을 쓰도록 variant를 단다.
"""
from __future__ import annotations

from datetime import date, datetime

from sqlalchemy import JSON, BigInteger, Boolean, Date, DateTime, Float, Index, Integer, SmallInteger, String, Text
from sqlalchemy.dialects import mysql
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

DateTime3 = DateTime().with_variant(mysql.DATETIME(fsp=3), "mysql")
Double = Float().with_variant(mysql.DOUBLE(), "mysql")
Id = BigInteger().with_variant(Integer(), "sqlite")   # SQLite 자동 증가는 INTEGER PRIMARY KEY만 된다


class Base(DeclarativeBase):
    pass


class EtlLog(Base):
    __tablename__ = "etl_log"
    id: Mapped[int] = mapped_column(Id, primary_key=True, autoincrement=True)
    job: Mapped[str] = mapped_column(String(20))                 # load_base | update_market | fill_financials
    started_at: Mapped[datetime] = mapped_column(DateTime3)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime3)
    status: Mapped[str] = mapped_column(String(10))              # RUNNING | SUCCESS | FAILED
    steps: Mapped[dict | list | None] = mapped_column(JSON)
    message: Mapped[str | None] = mapped_column(Text)


class TradingDay(Base):
    __tablename__ = "trading_day"
    date: Mapped[date] = mapped_column(Date, primary_key=True)


class Stock(Base):
    __tablename__ = "stock"
    code: Mapped[str] = mapped_column(String(10), primary_key=True)
    name: Mapped[str] = mapped_column(String(100))
    market: Mapped[str] = mapped_column(String(10), default="KOSPI")
    sector: Mapped[str] = mapped_column(String(50))
    sector_group: Mapped[str] = mapped_column(String(50))
    is_managed: Mapped[bool] = mapped_column(Boolean, default=False)
    managed_date: Mapped[date | None] = mapped_column(Date)
    first_seen: Mapped[date | None] = mapped_column(Date)


class PriceDaily(Base):
    __tablename__ = "price_daily"
    code: Mapped[str] = mapped_column(String(10), primary_key=True)
    date: Mapped[date] = mapped_column(Date, primary_key=True)
    close: Mapped[float] = mapped_column(Double)
    __table_args__ = (Index("ix_price_date", "date"),)


class PriceFlag(Base):
    __tablename__ = "price_flag"
    code: Mapped[str] = mapped_column(String(10), primary_key=True)
    date: Mapped[date] = mapped_column(Date, primary_key=True)
    change_ratio: Mapped[float] = mapped_column(Double)
    checked: Mapped[bool] = mapped_column(Boolean, default=False)


class IndexDaily(Base):
    __tablename__ = "index_daily"
    index_code: Mapped[str] = mapped_column(String(10), primary_key=True)   # 'KOSPI'
    date: Mapped[date] = mapped_column(Date, primary_key=True)
    close: Mapped[float] = mapped_column(Double)


class RateDaily(Base):
    __tablename__ = "rate_daily"
    series: Mapped[str] = mapped_column(String(10), primary_key=True)       # 'KTB3Y' (%)
    date: Mapped[date] = mapped_column(Date, primary_key=True)
    value: Mapped[float] = mapped_column(Double)


class BondIndexDaily(Base):
    __tablename__ = "bond_index_daily"
    date: Mapped[date] = mapped_column(Date, primary_key=True)
    ret: Mapped[float] = mapped_column(Double)
    idx: Mapped[float] = mapped_column(Double)


class FinancialAnnual(Base):
    """금액은 천원, 주당값(eps·bps·sps·ceps)은 원."""
    __tablename__ = "financial_annual"
    code: Mapped[str] = mapped_column(String(10), primary_key=True)
    fiscal_year: Mapped[int] = mapped_column(SmallInteger, primary_key=True)
    settle_month: Mapped[int | None] = mapped_column(SmallInteger)
    apply_date: Mapped[date] = mapped_column(Date)
    total_assets: Mapped[float | None] = mapped_column(Double)
    total_liabilities: Mapped[float | None] = mapped_column(Double)
    total_equity: Mapped[float | None] = mapped_column(Double)
    capital: Mapped[float | None] = mapped_column(Double)
    retained_earnings: Mapped[float | None] = mapped_column(Double)
    revenue: Mapped[float | None] = mapped_column(Double)
    operating_income: Mapped[float | None] = mapped_column(Double)
    net_income: Mapped[float | None] = mapped_column(Double)
    dividends: Mapped[float | None] = mapped_column(Double)
    eps: Mapped[float | None] = mapped_column(Double)
    bps: Mapped[float | None] = mapped_column(Double)
    sps: Mapped[float | None] = mapped_column(Double)
    ceps: Mapped[float | None] = mapped_column(Double)
    source: Mapped[str] = mapped_column(String(5))                   # FILE | DART
    __table_args__ = (Index("ix_fin_apply", "apply_date"),)


class BacktestRun(Base):
    __tablename__ = "backtest_run"
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    status: Mapped[str] = mapped_column(String(10))                  # QUEUED | RUNNING | DONE | FAILED
    progress: Mapped[float] = mapped_column(Double, default=0.0)
    params: Mapped[dict] = mapped_column(JSON)
    data_version: Mapped[int] = mapped_column(BigInteger)
    rebalance_dates: Mapped[list | None] = mapped_column(JSON)
    result_summary: Mapped[dict | None] = mapped_column(JSON)
    error: Mapped[str | None] = mapped_column(Text)
    elapsed_ms: Mapped[int | None] = mapped_column(Integer)
    created_at: Mapped[datetime] = mapped_column(DateTime3)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime3)


class BacktestNav(Base):
    __tablename__ = "backtest_nav"
    backtest_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    strategy: Mapped[str] = mapped_column(String(40), primary_key=True)
    date: Mapped[date] = mapped_column(Date, primary_key=True)
    nav: Mapped[float] = mapped_column(Double)


class BacktestMetric(Base):
    __tablename__ = "backtest_metric"
    backtest_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    strategy: Mapped[str] = mapped_column(String(40), primary_key=True)
    metric: Mapped[str] = mapped_column(String(40), primary_key=True)
    value: Mapped[float | None] = mapped_column(Double)


class BacktestYearly(Base):
    __tablename__ = "backtest_yearly"
    backtest_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    strategy: Mapped[str] = mapped_column(String(40), primary_key=True)
    year: Mapped[int] = mapped_column(SmallInteger, primary_key=True)
    ret: Mapped[float | None] = mapped_column(Double)


class BacktestHolding(Base):
    __tablename__ = "backtest_holding"
    backtest_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    strategy: Mapped[str] = mapped_column(String(40), primary_key=True)
    date: Mapped[date] = mapped_column(Date, primary_key=True)
    asset: Mapped[str] = mapped_column(String(10), primary_key=True)
    weight: Mapped[float] = mapped_column(Double)


class ProfileBacktestCache(Base):
    __tablename__ = "profile_backtest_cache"
    settings_hash: Mapped[str] = mapped_column(String(64), primary_key=True)
    data_version: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    result: Mapped[dict] = mapped_column(JSON)
    created_at: Mapped[datetime] = mapped_column(DateTime3)


MARKET_TABLES = (
    "etl_log", "trading_day", "stock", "price_daily", "price_flag", "index_daily", "rate_daily",
    "bond_index_daily", "financial_annual",
)
