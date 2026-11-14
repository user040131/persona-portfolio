"""FastAPI 앱 (docs/02 §4).

실행:  uvicorn app.main:app --host 127.0.0.1 --port 8000
"""
from __future__ import annotations

import logging
import time
import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request

from app import errors
from app.api.internal.v1 import router as internal_v1
from app.config import Settings, get_settings
from app.data.market_data import MarketData
from app.db.engine import init_schema, make_engine, session_factory
from app.services import backtest_jobs

log = logging.getLogger("quant-engine")
REQUEST_ID_HEADER = "X-Request-Id"


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        engine = make_engine(settings.database_url)
        init_schema(engine)
        app.state.engine = engine
        app.state.sessions = session_factory(engine)
        backtest_jobs.recover(app.state.sessions)
        app.state.market = None
        if settings.load_market_on_startup:
            try:
                app.state.market = MarketData.from_db(engine, settings.cache_dir,
                                                      (settings.fin_apply_month, settings.fin_apply_day))
            except Exception:  # noqa: BLE001  데이터가 깨져도 상태 API는 떠 있어야 한다
                log.exception("시장 데이터를 읽지 못했어요. 업무 API는 503을 냅니다")
        log.info("quant-engine started (db=%s, market=%s)", engine.url.render_as_string(hide_password=True),
                 "none" if app.state.market is None else f"v{app.state.market.data_version}")
        yield
        engine.dispose()

    app = FastAPI(title="quant-engine", version="0.1.0", lifespan=lifespan,
                  docs_url="/internal/docs", openapi_url="/internal/openapi.json")
    app.state.settings = settings
    errors.install(app)

    @app.middleware("http")
    async def request_log(request: Request, call_next):  # noqa: ANN001, ANN202
        # Spring이 만든 X-Request-Id를 이어받아 양쪽 로그를 맞춘다 (docs/02 §5)
        request_id = request.headers.get(REQUEST_ID_HEADER) or uuid.uuid4().hex[:12]
        started = time.perf_counter()
        response = await call_next(request)
        response.headers[REQUEST_ID_HEADER] = request_id
        log.info("%s %s %s %dms rid=%s", request.method, request.url.path, response.status_code,
                 (time.perf_counter() - started) * 1000, request_id)
        return response

    @app.get("/livez", include_in_schema=False)
    def livez() -> dict[str, str]:
        """컨테이너 생존 확인용. 서비스 키 없이 열려 있고 데이터 정보는 주지 않는다."""
        return {"status": "ok"}

    app.include_router(internal_v1)
    return app


logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
app = create_app()
