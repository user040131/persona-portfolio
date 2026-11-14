"""I2. 데이터 상태. 데이터가 없을 때도 200으로 data_loaded=false를 준다 (docs/03 §2.4 #2)."""
from datetime import date, datetime

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.api.deps import get_db
from app.data.status import read_status

router = APIRouter()


class DataStatusResponse(BaseModel):
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


@router.get("/meta/data-status", response_model=DataStatusResponse)
def data_status(db: Session = Depends(get_db)) -> DataStatusResponse:
    return DataStatusResponse(**read_status(db).__dict__)
