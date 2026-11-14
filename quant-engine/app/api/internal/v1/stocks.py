"""I3 업종, I4 종목 목록, I5 종목 상세 (docs/03 §3)."""
from __future__ import annotations

from datetime import date

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from app.api.deps import get_market, get_settings
from app.config import Settings
from app.data.market_data import MarketData
from app.errors import NotFound
from app.services import stock_service

router = APIRouter()


class StockItemModel(BaseModel):
    code: str
    name: str
    sector: str
    sector_group: str
    is_managed: bool
    last_close: float | None
    delist_rules: list[str]


class StocksResponse(BaseModel):
    as_of: date
    data_version: int
    items: list[StockItemModel]


class StockResponse(StockItemModel):
    as_of: date
    data_version: int


class SectorModel(BaseModel):
    sector: str
    sector_group: str
    stock_count: int


class SectorsResponse(BaseModel):
    as_of: date
    data_version: int
    sectors: list[SectorModel]


@router.get("/stocks", response_model=StocksResponse)
def stocks(md: MarketData = Depends(get_market), settings: Settings = Depends(get_settings)) -> StocksResponse:
    items = stock_service.latest_items(md, settings)
    return StocksResponse(as_of=md.as_of.date(), data_version=md.data_version,
                          items=[StockItemModel(**i.__dict__) for i in items])


@router.get("/stocks/{code}", response_model=StockResponse)
def stock(code: str, md: MarketData = Depends(get_market), settings: Settings = Depends(get_settings)) -> StockResponse:
    for i in stock_service.latest_items(md, settings):
        if i.code == code:
            return StockResponse(**i.__dict__, as_of=md.as_of.date(), data_version=md.data_version)
    raise NotFound(f"stock {code} not found", {"code": code}, code="STOCK_NOT_FOUND")


@router.get("/meta/sectors", response_model=SectorsResponse)
def sectors(md: MarketData = Depends(get_market)) -> SectorsResponse:
    return SectorsResponse(as_of=md.as_of.date(), data_version=md.data_version,
                           sectors=[SectorModel(**s) for s in stock_service.sectors(md)])
