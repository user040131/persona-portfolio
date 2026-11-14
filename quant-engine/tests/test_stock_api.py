"""I3·I4·I5·I15 API (합성 시장 데이터를 앱에 넣고 확인)."""
from __future__ import annotations

from fastapi.testclient import TestClient

from app.data.market_data import MarketData
from tests.factories import tiny_tables


def _with_market(client: TestClient) -> None:
    client.app.state.market = MarketData.from_frames(tiny_tables(), data_version=7)


def test_시장_데이터가_없으면_종목_API는_503(client: TestClient) -> None:
    r = client.get("/internal/v1/stocks")
    assert r.status_code == 503
    assert r.json()["error"]["code"] == "DATA_NOT_LOADED"


def test_종목_목록_I4_형식(client: TestClient) -> None:
    _with_market(client)
    body = client.get("/internal/v1/stocks").json()
    assert body["as_of"] == "2026-08-19" and body["data_version"] == 7
    assert body["items"] == [{"code": "005930", "name": "삼성전자", "sector": "전기·전자", "sector_group": "전기·전자",
                              "is_managed": False, "last_close": 247500.0, "delist_rules": []}]


def test_종목_상세와_없는_종목(client: TestClient) -> None:
    _with_market(client)
    assert client.get("/internal/v1/stocks/005930").json()["name"] == "삼성전자"
    r = client.get("/internal/v1/stocks/999999")
    assert r.status_code == 404 and r.json()["error"]["code"] == "STOCK_NOT_FOUND"


def test_업종_목록_I3(client: TestClient) -> None:
    _with_market(client)
    body = client.get("/internal/v1/meta/sectors").json()
    assert body["sectors"] == [{"sector": "전기·전자", "sector_group": "전기·전자", "stock_count": 1}]


def test_reload는_DB에_데이터가_없으면_비운다(client: TestClient) -> None:
    _with_market(client)
    body = client.post("/internal/v1/admin/reload").json()
    assert body == {"data_loaded": False, "data_version": None}
    assert client.get("/internal/v1/stocks").status_code == 503


def test_reload도_서비스_키가_필요하다(anonymous: TestClient) -> None:
    assert anonymous.post("/internal/v1/admin/reload").status_code == 401
