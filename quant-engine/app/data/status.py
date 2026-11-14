"""데이터 적재 상태 (docs/02 §4.3, 03 §3 I1·I2).

- data_loaded: load_base가 한 번이라도 성공했는가
- data_version: 성공한 배치 중 가장 최근 etl_log.id
- as_of: 가장 최근 영업일
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.db.models import EtlLog, FinancialAnnual, Stock, TradingDay


@dataclass(frozen=True)
class DataStatus:
    data_loaded: bool
    data_version: int | None
    as_of: date | None
    price_start: date | None
    trading_days: int
    stock_count: int
    financial_year_max: int | None
    financial_dart_rows: int
    last_load_base: datetime | None
    last_update_market: datetime | None
    last_fill_financials: datetime | None


def _last_success(db: Session, job: str) -> datetime | None:
    return db.scalar(select(func.max(EtlLog.finished_at)).where(EtlLog.job == job, EtlLog.status == "SUCCESS"))


def read_status(db: Session) -> DataStatus:
    last_load_base = _last_success(db, "load_base")
    return DataStatus(
        data_loaded=last_load_base is not None,
        data_version=db.scalar(select(func.max(EtlLog.id)).where(EtlLog.status == "SUCCESS")),
        as_of=db.scalar(select(func.max(TradingDay.date))),
        price_start=db.scalar(select(func.min(TradingDay.date))),
        trading_days=db.scalar(select(func.count()).select_from(TradingDay)) or 0,
        stock_count=db.scalar(select(func.count()).select_from(Stock)) or 0,
        financial_year_max=db.scalar(select(func.max(FinancialAnnual.fiscal_year))),
        financial_dart_rows=db.scalar(select(func.count()).select_from(FinancialAnnual)
                                      .where(FinancialAnnual.source == "DART")) or 0,
        last_load_base=last_load_base,
        last_update_market=_last_success(db, "update_market"),
        last_fill_financials=_last_success(db, "fill_financials"),
    )
