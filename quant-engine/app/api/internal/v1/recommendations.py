"""I6. 추천 계산 (docs/03 §3). 저장하지 않는 순수 계산이다. Spring이 결과를 버전으로 저장한다."""
from __future__ import annotations

from typing import Any, Literal

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field, field_validator

from app.api.deps import get_market, get_settings
from app.config import Settings
from app.data.market_data import MarketData
from app.domain.allocation import InsufficientHistory
from app.domain.recommend import NoCandidates, UnknownStocks
from app.domain.types import FACTOR_ORDER, Edits, Existing, ProfileSettings
from app.errors import Conflict, Unprocessable
from app.services import compute_service

router = APIRouter()

Method = Literal["EQUAL", "INVERSE_VOL", "MIN_VARIANCE"]


class SettingsModel(BaseModel):
    """docs/03 §1.2 ProfileSettings."""
    grade: Literal["STABLE", "NEUTRAL", "AGGRESSIVE"]
    loss_limit: float | None = Field(default=None, lt=0)
    min_cash: float = Field(ge=0, le=1)
    stock_cap: float = Field(ge=0, le=1)
    n_stocks: int = Field(ge=1, le=50)
    factor_weights: dict[str, float]
    vol_exclude_top_pct: float = Field(ge=0, lt=1)
    exclude_overheat: bool
    stock_limit: float = Field(gt=0, le=1)
    sector_limit: float = Field(gt=0, le=1)
    default_method: Method
    excluded_sectors: list[str] = []

    @field_validator("factor_weights")
    @classmethod
    def _weights(cls, v: dict[str, float]) -> dict[str, float]:
        unknown = set(v) - set(FACTOR_ORDER)
        if unknown:
            raise ValueError(f"unknown factors: {sorted(unknown)}")
        if any(w < 0 for w in v.values()) or abs(sum(v.values()) - 1) > 0.01:
            raise ValueError("factor weights must be ≥ 0 and sum to 1")
        return v

    def to_domain(self) -> ProfileSettings:
        return ProfileSettings(**{**self.model_dump(), "excluded_sectors": tuple(self.excluded_sectors)})


class HoldingModel(BaseModel):
    code: str
    quantity: int = Field(ge=1)


class ExistingModel(BaseModel):
    holdings: list[HoldingModel] = []
    bond_units: float = Field(default=0, ge=0)
    cash: int = Field(default=0, ge=0)

    def to_domain(self) -> Existing:
        merged: dict[str, int] = {}
        for h in self.holdings:
            merged[h.code] = merged.get(h.code, 0) + h.quantity
        return Existing(merged, self.bond_units, self.cash)


class EditsModel(BaseModel):
    removed_codes: list[str] = []
    safe_ratio: float | None = Field(default=None, ge=0, le=1)
    method: Method | None = None

    def to_domain(self) -> Edits:
        return Edits(tuple(self.removed_codes), self.safe_ratio, self.method)


class ComputeRequest(BaseModel):
    settings: SettingsModel
    invest_amount: int = Field(gt=0)
    existing: ExistingModel = ExistingModel()
    edits: EditsModel = EditsModel()


@router.post("/recommendations/compute")
def compute(req: ComputeRequest, md: MarketData = Depends(get_market),
            settings: Settings = Depends(get_settings)) -> dict[str, Any]:
    try:
        return compute_service.compute(md, settings, req.settings.to_domain(), req.invest_amount,
                                       req.existing.to_domain(), req.edits.to_domain())
    except UnknownStocks as e:
        raise Unprocessable(str(e), {"codes": e.codes}, code="UNKNOWN_STOCK") from e
    except NoCandidates as e:
        raise Conflict(str(e), code="INSUFFICIENT_UNIVERSE") from e
    except InsufficientHistory as e:
        raise Conflict(str(e), code="INSUFFICIENT_HISTORY") from e
