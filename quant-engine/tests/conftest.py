"""공통 픽스처. 단위 테스트는 SQLite 메모리 DB로 앱을 띄운다."""
from __future__ import annotations

from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from app.config import BASE_DIR, Settings
from app.main import create_app

SERVICE_KEY = "test-service-key"
RAW_DIR = BASE_DIR / "data" / "raw"


@pytest.fixture
def settings() -> Settings:
    return Settings(database_url="sqlite+pysqlite://", quant_service_key=SERVICE_KEY, cache_dir=None)


@pytest.fixture
def client(settings: Settings) -> Iterator[TestClient]:
    with TestClient(create_app(settings), headers={"X-Service-Key": SERVICE_KEY}) as c:
        yield c


@pytest.fixture
def anonymous(settings: Settings) -> Iterator[TestClient]:
    """서비스 키 없이 부르는 클라이언트."""
    with TestClient(create_app(settings)) as c:
        yield c


@pytest.fixture(scope="session")
def mysql_url() -> Iterator[str]:
    """실제 MySQL 8.4 컨테이너 (세션 동안 하나). Docker가 없으면 건너뛴다."""
    try:
        import docker

        docker.from_env().ping()
    except Exception:  # noqa: BLE001
        pytest.skip("Docker를 쓸 수 없어 건너뜀")
    try:
        from testcontainers.community.mysql import MySqlContainer   # testcontainers 4.x 새 경로
    except ImportError:
        from testcontainers.mysql import MySqlContainer

    with MySqlContainer("mysql:8.4", dialect="pymysql") as c:
        yield c.get_connection_url()


def raw_files_present() -> bool:
    from app.etl.sources import missing

    return not missing(RAW_DIR)


@pytest.fixture(scope="session")
def real_frames():
    """실제 제공 파일로 만든 적재 행 (1분 정도). 원본이 없으면 건너뛴다."""
    if not raw_files_present():
        pytest.skip("data/raw에 제공 파일이 없음")
    from app.etl.load_base import build_frames

    s = Settings()
    return build_frames(RAW_DIR, s.config_dir, s)


@pytest.fixture(scope="session")
def real_market(real_frames):
    from app.data.market_data import MarketData

    return MarketData.from_frames(real_frames.tables, data_version=1)
