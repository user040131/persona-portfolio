"""I15 캐시 다시 읽기. 배치를 다시 돌린 뒤 운영자가 부른다 (docs/02 §4.3)."""
from __future__ import annotations

from fastapi import APIRouter, Request
from pydantic import BaseModel

from app.data.market_data import MarketData

router = APIRouter()


class ReloadResponse(BaseModel):
    data_loaded: bool
    data_version: int | None


@router.post("/admin/reload", response_model=ReloadResponse)
def reload(request: Request) -> ReloadResponse:
    state = request.app.state
    md = MarketData.from_db(state.engine, cache_dir=state.settings.cache_dir,
                            fin_apply=(state.settings.fin_apply_month, state.settings.fin_apply_day))
    state.market = md   # 참조를 한 번에 바꾼다. 진행 중인 요청은 이전 데이터로 끝난다
    return ReloadResponse(data_loaded=md is not None, data_version=None if md is None else md.data_version)
