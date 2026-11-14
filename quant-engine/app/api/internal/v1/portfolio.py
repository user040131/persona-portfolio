"""I8 포트폴리오 평가 + 알림 조건, I9 리밸런싱 수량 (docs/03 §3, §1.10·1.11). 저장하지 않는 순수 계산이다."""
from __future__ import annotations

from datetime import date
from typing import Any, Literal

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field

from app.api.deps import get_market, get_settings
from app.api.internal.v1.recommendations import HoldingModel, SettingsModel
from app.config import Settings
from app.data.market_data import MarketData
from app.domain import alerts
from app.domain.recommend import UnknownStocks
from app.errors import Conflict, Unprocessable
from app.services import portfolio_service

router = APIRouter()


class EvaluateRequest(BaseModel):
    settings: SettingsModel
    holdings: list[HoldingModel] = []
    bond_units: float = Field(default=0, ge=0)
    cash: int = Field(default=0, ge=0)
    targets: dict[str, float] = {}
    purchased_at: date | None = None
    holdings_changed_at: date | None = None
    last_r6_year: int | None = None

    def to_state(self) -> alerts.PortfolioState:
        merged: dict[str, int] = {}
        for h in self.holdings:
            merged[h.code] = merged.get(h.code, 0) + h.quantity
        return alerts.PortfolioState(merged, self.bond_units, self.cash, dict(self.targets), self.purchased_at,
                                     self.holdings_changed_at, self.last_r6_year)

    def to_limits(self) -> alerts.Limits:
        s = self.settings
        return alerts.Limits(s.stock_limit, s.sector_limit, s.loss_limit)


@router.post("/portfolio/evaluate")
def evaluate(req: EvaluateRequest, md: MarketData = Depends(get_market),
             settings: Settings = Depends(get_settings)) -> dict[str, Any]:
    try:
        return portfolio_service.evaluate(md, settings, req.to_state(), req.to_limits())
    except UnknownStocks as e:
        raise Unprocessable(str(e), {"codes": e.codes}, code="UNKNOWN_STOCK") from e


class RebalanceRequest(EvaluateRequest):
    alert_type: Literal["R1", "R2", "R3", "R4", "R5", "R6", "R7"]
    alert_target: str = ""


@router.post("/portfolio/rebalance-plan")
def rebalance_plan(req: RebalanceRequest, md: MarketData = Depends(get_market),
                   settings: Settings = Depends(get_settings)) -> dict[str, Any]:
    try:
        return portfolio_service.rebalance_plan(md, settings, req.to_state(), req.to_limits(),
                                                req.settings.to_domain(), req.alert_type, req.alert_target)
    except UnknownStocks as e:
        raise Unprocessable(str(e), {"codes": e.codes}, code="UNKNOWN_STOCK") from e
    except ValueError as e:
        raise Conflict(str(e), code="NOTHING_TO_REBALANCE") from e
