"""백테스트 작업 (I10~I14, 01 FR-BT-13). 엔진 프로세스 안의 작업자 스레드 하나가 차례로 돌린다.

결과는 market 스키마 backtest_* 테이블에 저장한다 (docs/03 §4.1).
"""
from __future__ import annotations

import hashlib
import json
import logging
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime

from sqlalchemy import delete, insert, select, update
from sqlalchemy.orm import Session, sessionmaker

from app.config import Settings
from app.data.market_data import MarketData
from app.db.models import (BacktestHolding, BacktestMetric, BacktestNav, BacktestRun, BacktestYearly,
                           ProfileBacktestCache)
from app.domain.types import ProfileSettings
from app.services import backtest_service
from app.services.backtest_service import Params

log = logging.getLogger("quant-engine")
_worker = ThreadPoolExecutor(max_workers=1, thread_name_prefix="backtest")   # 무거운 계산이라 한 번에 하나만
QUEUED, RUNNING, DONE, FAILED = "QUEUED", "RUNNING", "DONE", "FAILED"


def submit(sessions: sessionmaker[Session], md: MarketData, s: Settings, raw: dict, p: Params) -> str:
    job_id = str(uuid.uuid4())
    with sessions() as db:
        db.add(BacktestRun(id=job_id, status=QUEUED, progress=0.0, params=raw, data_version=md.data_version,
                           created_at=datetime.now()))
        db.commit()
    _worker.submit(_work, sessions, md, s, job_id, p)
    return job_id


def _set(sessions: sessionmaker[Session], job_id: str, **values) -> None:  # noqa: ANN003
    with sessions() as db:
        db.execute(update(BacktestRun).where(BacktestRun.id == job_id).values(**values))
        db.commit()


def _work(sessions: sessionmaker[Session], md: MarketData, s: Settings, job_id: str, p: Params) -> None:
    _set(sessions, job_id, status=RUNNING)
    try:
        res = backtest_service.run(md, s, p, lambda x: _set(sessions, job_id, progress=round(x, 3)))
        _save(sessions, job_id, res)
        summary = {k: v for k, v in res.items() if k not in ("navs", "holdings")}
        _set(sessions, job_id, status=DONE, progress=1.0, rebalance_dates=res["rebalance_dates"],
             result_summary=summary, elapsed_ms=res["elapsed_ms"], finished_at=datetime.now())
    except Exception as e:  # noqa: BLE001  작업 실패는 상태로 알린다
        log.exception("backtest %s failed", job_id)
        _set(sessions, job_id, status=FAILED, error=str(e)[:2000], finished_at=datetime.now())


def _save(sessions: sessionmaker[Session], job_id: str, res: dict) -> None:
    navs = [{"backtest_id": job_id, "strategy": k, "date": d.date(), "nav": float(v)}
            for k, nav in res["navs"].items() for d, v in nav.items()]
    holdings = [{"backtest_id": job_id, "strategy": k, "date": d.date(), "asset": a, "weight": float(w)}
                for k, ts in res["holdings"].items() for d, ws in ts.items() for a, w in ws.items() if w > 0]
    metrics = [{"backtest_id": job_id, "strategy": x["strategy"], "metric": m, "value": float(v)}
               for x in res["strategies"] for m, v in x["metrics"].items()]
    yearly = [{"backtest_id": job_id, "strategy": x["strategy"], "year": y["year"], "ret": y["return"]}
              for x in res["strategies"] for y in x["yearly"]]
    with sessions() as db:
        for model, rows in ((BacktestNav, navs), (BacktestHolding, holdings), (BacktestMetric, metrics),
                            (BacktestYearly, yearly)):
            if rows:
                db.execute(insert(model), rows)
        db.commit()


def recover(sessions: sessionmaker[Session]) -> None:
    """엔진이 다시 켜지면 하던 작업은 사라진다. 영원히 '진행 중'으로 보이지 않게 실패로 바꾼다."""
    with sessions() as db:
        db.execute(update(BacktestRun).where(BacktestRun.status.in_([QUEUED, RUNNING]))
                   .values(status=FAILED, error="엔진이 다시 시작돼 작업이 중단됐어요", finished_at=datetime.now()))
        db.commit()


def history_card(sessions: sessionmaker[Session], md: MarketData, s: Settings, settings: ProfileSettings,
                 raw_settings: dict, start_year: int) -> dict:
    """과거 성과 카드 (I7). (설정·시작 연도 해시, 데이터 버전)으로 캐시한다.
    shortcut: 캐시 키에 계산 코드 버전이 없다, 계산 규칙을 바꾸면 profile_backtest_cache를 비울 것"""
    key = hashlib.sha256(json.dumps({"settings": raw_settings, "start_year": start_year},
                                    sort_keys=True).encode()).hexdigest()
    with sessions() as db:
        hit = db.get(ProfileBacktestCache, (key, md.data_version))
        if hit is not None:
            return {**hit.result, "cached": True}
        card = backtest_service.history_card(md, s, settings, start_year)
        db.execute(delete(ProfileBacktestCache).where(ProfileBacktestCache.settings_hash == key))
        db.add(ProfileBacktestCache(settings_hash=key, data_version=md.data_version, result=card,
                                    created_at=datetime.now()))
        db.commit()
        return {**card, "cached": False}


def get_run(db: Session, job_id: str) -> BacktestRun | None:
    return db.get(BacktestRun, job_id)


def navs(db: Session, job_id: str) -> list:
    """(strategy, date, nav) 행. 전략 27개 × 11년이면 7만 행이 넘어 ORM 객체 대신 열만 읽는다."""
    return list(db.execute(select(BacktestNav.strategy, BacktestNav.date, BacktestNav.nav)
                           .where(BacktestNav.backtest_id == job_id)))


def holdings(db: Session, job_id: str, strategy: str) -> list[BacktestHolding]:
    return list(db.scalars(select(BacktestHolding).where(BacktestHolding.backtest_id == job_id,
                                                         BacktestHolding.strategy == strategy)
                           .order_by(BacktestHolding.date, BacktestHolding.weight.desc())))
