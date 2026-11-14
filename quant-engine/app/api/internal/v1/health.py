"""I1. 엔진 상태. 데이터가 없어도 200 (Spring 헬스체크용)."""
from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.api.deps import get_db
from app.data.status import read_status

router = APIRouter()


class HealthResponse(BaseModel):
    status: str
    data_loaded: bool


@router.get("/health", response_model=HealthResponse)
def health(db: Session = Depends(get_db)) -> HealthResponse:
    return HealthResponse(status="ok", data_loaded=read_status(db).data_loaded)
