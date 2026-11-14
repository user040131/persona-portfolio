"""서비스 키·오류 형식·데이터 상태 (docs/02 §5, 03 §2.2, §3 I1·I2)."""
from __future__ import annotations

from datetime import date, datetime

from fastapi.testclient import TestClient

from app.db.models import EtlLog, Stock, TradingDay


def test_서비스_키가_없으면_401_공통_오류_형식(anonymous: TestClient) -> None:
    r = anonymous.get("/internal/v1/health")
    assert r.status_code == 401
    assert r.json() == {"error": {"code": "UNAUTHORIZED", "message": "missing or invalid X-Service-Key", "detail": {}}}


def test_서비스_키가_틀려도_401(anonymous: TestClient) -> None:
    r = anonymous.get("/internal/v1/health", headers={"X-Service-Key": "wrong"})
    assert r.status_code == 401


def test_livez는_키_없이_열려_있다(anonymous: TestClient) -> None:
    assert anonymous.get("/livez").json() == {"status": "ok"}


def test_데이터가_없어도_health는_200(client: TestClient) -> None:
    r = client.get("/internal/v1/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok", "data_loaded": False}


def test_없는_경로는_공통_형식_404(client: TestClient) -> None:
    r = client.get("/internal/v1/nope")
    assert r.status_code == 404
    assert r.json()["error"]["code"] == "NOT_FOUND"


def test_요청_ID를_이어받아_돌려준다(client: TestClient) -> None:
    r = client.get("/internal/v1/health", headers={"X-Request-Id": "abc123"})
    assert r.headers["X-Request-Id"] == "abc123"


def test_data_status는_적재_전이면_비어_있다(client: TestClient) -> None:
    body = client.get("/internal/v1/meta/data-status").json()
    assert body["data_loaded"] is False
    assert body["data_version"] is None
    assert body["as_of"] is None
    assert body["stock_count"] == 0


def test_load_base가_성공하면_data_loaded와_버전이_생긴다(client: TestClient) -> None:
    with client.app.state.sessions() as db:
        db.add(EtlLog(job="load_base", started_at=datetime(2026, 11, 11, 1), finished_at=datetime(2026, 11, 11, 1, 2),
                      status="SUCCESS"))
        db.add(EtlLog(job="update_market", started_at=datetime(2026, 11, 11, 2), status="FAILED"))
        db.add_all([TradingDay(date=date(2026, 8, 18)), TradingDay(date=date(2026, 8, 19))])
        db.add(Stock(code="005930", name="삼성전자", sector="전기·전자", sector_group="전기·전자"))
        db.commit()

    body = client.get("/internal/v1/meta/data-status").json()
    assert body["data_loaded"] is True
    assert body["data_version"] == 1          # 실패한 배치는 버전이 아니다
    assert body["as_of"] == "2026-08-19"
    assert body["price_start"] == "2026-08-18"
    assert body["stock_count"] == 1
    assert client.get("/internal/v1/health").json()["data_loaded"] is True
