"""서비스 키 검사 (docs/02 §5). Spring만 엔진을 부를 수 있게 한다."""
from __future__ import annotations

import secrets

from fastapi import Header, Request

from app.errors import Unauthorized

SERVICE_KEY_HEADER = "X-Service-Key"


def require_service_key(request: Request, x_service_key: str | None = Header(default=None, alias=SERVICE_KEY_HEADER)) -> None:
    expected: str = request.app.state.settings.quant_service_key
    # 길이가 달라도 시간이 같게 걸리도록 compare_digest로 비교
    if not x_service_key or not secrets.compare_digest(x_service_key.encode(), expected.encode()):
        raise Unauthorized("missing or invalid X-Service-Key")
