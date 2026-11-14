"""내부 API /internal/v1 (docs/03 §3). 모든 경로에 X-Service-Key가 필요하다."""
from fastapi import APIRouter, Depends

from app.api.internal.v1 import admin, backtests, health, meta, portfolio, recommendations, stocks
from app.security import require_service_key

router = APIRouter(prefix="/internal/v1", dependencies=[Depends(require_service_key)])
router.include_router(health.router)
router.include_router(meta.router)
router.include_router(stocks.router)
router.include_router(recommendations.router)
router.include_router(portfolio.router)
router.include_router(backtests.router)
router.include_router(admin.router)
