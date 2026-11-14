"""공통 오류 형식 (docs/03 §2.2).

    {"error": {"code": "...", "message": "...", "detail": {...}}}

엔진의 message는 개발용 영어 문장이다. 사용자에게 보이는 한국어 문구는 Spring이 코드별로 채운다.
"""
from __future__ import annotations

import logging
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

log = logging.getLogger(__name__)


class AppError(Exception):
    status_code = 500
    code = "INTERNAL_ERROR"

    def __init__(self, message: str | None = None, detail: dict[str, Any] | None = None, code: str | None = None):
        super().__init__(message or self.code)
        self.message = message or self.code
        self.detail = detail or {}
        if code:
            self.code = code


class Unauthorized(AppError):
    status_code = 401
    code = "UNAUTHORIZED"


class NotFound(AppError):
    status_code = 404
    code = "NOT_FOUND"


class Conflict(AppError):
    """INSUFFICIENT_UNIVERSE, INSUFFICIENT_HISTORY 등. code를 넘겨서 쓴다."""
    status_code = 409
    code = "CONFLICT"


class Unprocessable(AppError):
    status_code = 422
    code = "VALIDATION_ERROR"


class DataNotLoaded(AppError):
    status_code = 503
    code = "DATA_NOT_LOADED"

    def __init__(self) -> None:
        super().__init__("market data is not loaded; run `python -m app.etl.load_base`")


def body(code: str, message: str, detail: dict[str, Any] | None = None) -> dict[str, Any]:
    return {"error": {"code": code, "message": message, "detail": detail or {}}}


_HTTP_CODES = {401: "UNAUTHORIZED", 403: "FORBIDDEN", 404: "NOT_FOUND", 405: "METHOD_NOT_ALLOWED"}


def install(app: FastAPI) -> None:
    @app.exception_handler(AppError)
    async def _app_error(_: Request, e: AppError) -> JSONResponse:
        return JSONResponse(body(e.code, e.message, e.detail), status_code=e.status_code)

    @app.exception_handler(RequestValidationError)
    async def _validation(_: Request, e: RequestValidationError) -> JSONResponse:
        problems = [{"loc": list(err["loc"]), "msg": err["msg"]} for err in e.errors()]
        return JSONResponse(body("VALIDATION_ERROR", "invalid request", {"problems": problems}), status_code=422)

    @app.exception_handler(StarletteHTTPException)
    async def _http(_: Request, e: StarletteHTTPException) -> JSONResponse:
        code = _HTTP_CODES.get(e.status_code, "HTTP_ERROR")
        return JSONResponse(body(code, str(e.detail)), status_code=e.status_code)

    @app.exception_handler(Exception)
    async def _unexpected(_: Request, e: Exception) -> JSONResponse:
        log.exception("unhandled error")
        return JSONResponse(body("INTERNAL_ERROR", "internal error"), status_code=500)
