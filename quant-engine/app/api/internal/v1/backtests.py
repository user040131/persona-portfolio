"""I7 과거 성과 카드, I10~I14 백테스트 작업 (docs/03 §3)."""
from __future__ import annotations

import csv
import io
from collections import defaultdict
from datetime import date
from typing import Any, Literal

import pandas as pd
from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import Response
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.api.deps import get_db, get_market, get_settings
from app.api.internal.v1.recommendations import SettingsModel
from app.config import Settings
from app.data.market_data import MarketData
from app.domain import metrics
from app.errors import NotFound, Unprocessable
from app.services import backtest_jobs
from app.services.backtest_service import Params, Profile

router = APIRouter()


class ProfileModel(BaseModel):
    key: str = Field(pattern=r"^[A-Z0-9_]{1,30}$")
    settings: SettingsModel


class BacktestRequest(BaseModel):
    start_year: int = Field(default=2015, ge=2011, le=2100)
    end_date: date | None = None
    trading_cost_rate: float = Field(default=0.003, ge=0, le=0.05)
    experiments: list[Literal["A", "B", "C", "D"]] = ["A", "B", "C", "D"]
    multi_weights: dict[str, float] = {"VALUE": 0.30, "QUALITY": 0.30, "MOMENTUM": 0.25, "STABILITY": 0.15,
                                       "DIVIDEND": 0.0}
    profiles: list[ProfileModel] = []

    def to_params(self) -> Params:
        return Params(self.start_year, pd.Timestamp(self.end_date) if self.end_date else None,
                      self.trading_cost_rate, tuple(dict.fromkeys(self.experiments)), dict(self.multi_weights),
                      tuple(Profile(p.key, p.settings.to_domain()) for p in self.profiles))


@router.post("/backtests", status_code=202)
def create(req: BacktestRequest, request: Request, md: MarketData = Depends(get_market),
           settings: Settings = Depends(get_settings)) -> dict[str, str]:
    if "D" in req.experiments and not req.profiles:
        raise Unprocessable("experiment D needs profiles", {"field": "profiles"})
    job_id = backtest_jobs.submit(request.app.state.sessions, md, settings, req.model_dump(mode="json"),
                                  req.to_params())
    return {"job_id": job_id, "status": backtest_jobs.QUEUED}


def _run(db: Session, job_id: str):  # noqa: ANN202
    run = backtest_jobs.get_run(db, job_id)
    if run is None:
        raise NotFound(f"backtest {job_id} not found", code="BACKTEST_NOT_FOUND")
    return run


@router.get("/backtests/{job_id}")
def status(job_id: str, db: Session = Depends(get_db)) -> dict[str, Any]:
    run = _run(db, job_id)
    return {"job_id": run.id, "status": run.status, "progress": run.progress, "error": run.error,
            "data_version": run.data_version, "params": run.params,
            "result": run.result_summary if run.status == backtest_jobs.DONE else None}


def _nav_frame(db: Session, job_id: str) -> pd.DataFrame:
    rows = backtest_jobs.navs(db, job_id)
    if not rows:
        return pd.DataFrame()
    df = pd.DataFrame([(r.strategy, r.date, r.nav) for r in rows], columns=["strategy", "date", "nav"])
    out = df.pivot(index="date", columns="strategy", values="nav").sort_index().astype(float)
    return out.set_axis(pd.to_datetime(out.index))


@router.get("/backtests/{job_id}/nav")
def nav(job_id: str, strategies: str | None = None, freq: Literal["D", "W", "M"] = "D",
        db: Session = Depends(get_db)) -> dict[str, Any]:
    _run(db, job_id)
    df = _nav_frame(db, job_id)
    if strategies:
        df = df.reindex(columns=[s for s in strategies.split(",") if s in df.columns])
    dd = df.apply(metrics.drawdown)            # 낙폭은 날마다로 계산한 뒤 줄인다
    if freq != "D" and len(df):
        # 기간마다 마지막 '실제 영업일'로 줄인다 (영업일이 하나도 없는 주(긴 연휴)는 자연히 빠진다)
        rule = {"W": "W", "M": "ME"}[freq]
        ends = pd.Series(df.index, index=df.index).resample(rule).max().dropna()
        dd = dd.resample(rule).min().loc[ends.index].set_axis(pd.DatetimeIndex(ends.values))
        df = df.loc[ends.values]
    return {"freq": freq, "dates": [d.date().isoformat() for d in df.index],
            "strategies": {k: {"nav": [round(float(v), 4) for v in df[k]],
                               "drawdown": [round(float(v), 4) for v in dd[k]]} for k in df.columns}}


@router.get("/backtests/{job_id}/holdings")
def holdings(job_id: str, strategy: str, date_: date | None = Query(default=None, alias="date"),
             db: Session = Depends(get_db)) -> dict[str, Any]:
    _run(db, job_id)
    by_date: dict[date, list] = defaultdict(list)
    for h in backtest_jobs.holdings(db, job_id, strategy):
        by_date[h.date].append({"asset": h.asset, "weight": round(float(h.weight), 6)})
    dates = sorted(by_date)
    pick = max((d for d in dates if date_ is None or d <= date_), default=None)
    return {"strategy": strategy, "dates": [d.isoformat() for d in dates],
            "date": pick.isoformat() if pick else None, "holdings": by_date.get(pick, [])}


@router.get("/backtests/{job_id}/export.csv")
def export(job_id: str, db: Session = Depends(get_db)) -> Response:
    _run(db, job_id)
    df = _nav_frame(db, job_id)
    buf = io.StringIO()
    w = csv.writer(buf)
    w.writerow(["date", *df.columns])
    for d, row in df.iterrows():
        w.writerow([d.date().isoformat(), *(f"{v:.4f}" for v in row)])
    return Response(buf.getvalue(), media_type="text/csv; charset=utf-8",
                    headers={"Content-Disposition": f'attachment; filename="backtest-{job_id}.csv"'})


class HistoryCardRequest(BaseModel):
    settings: SettingsModel
    start_year: int = Field(default=2015, ge=2011, le=2100)


@router.post("/history-card")
def history_card(req: HistoryCardRequest, request: Request, md: MarketData = Depends(get_market),
                 settings: Settings = Depends(get_settings)) -> dict[str, Any]:
    return backtest_jobs.history_card(request.app.state.sessions, md, settings, req.settings.to_domain(),
                                      req.settings.model_dump(mode="json"), req.start_year)
