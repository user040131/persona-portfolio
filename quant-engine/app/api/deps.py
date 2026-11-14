"""라우터 공통 의존성."""
from __future__ import annotations

from collections.abc import Iterator

from fastapi import Request
from sqlalchemy.orm import Session

from app.config import Settings
from app.data.market_data import MarketData
from app.errors import DataNotLoaded


def get_db(request: Request) -> Iterator[Session]:
    with request.app.state.sessions() as session:
        yield session


def get_settings(request: Request) -> Settings:
    return request.app.state.settings


def get_market(request: Request) -> MarketData:
    """메모리에 올린 시장 데이터. 없으면 503 DATA_NOT_LOADED (01 FR-META-05)."""
    md: MarketData | None = getattr(request.app.state, "market", None)
    if md is None:
        raise DataNotLoaded()
    return md
