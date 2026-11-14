"""DB 연결. 운영은 MySQL(market 스키마, quant_user 계정), 단위 테스트는 SQLite 메모리 DB."""
from __future__ import annotations

from sqlalchemy import Engine, create_engine, event
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from app.db.models import Base


def make_engine(url: str) -> Engine:
    if url.startswith("sqlite"):
        # 메모리 DB를 모든 연결이 같이 쓰도록 StaticPool
        engine = create_engine(url, connect_args={"check_same_thread": False}, poolclass=StaticPool)

        @event.listens_for(engine, "connect")
        def _fk_on(dbapi_conn, _):  # noqa: ANN001
            dbapi_conn.execute("PRAGMA foreign_keys=ON")

        return engine
    return create_engine(url, pool_pre_ping=True, pool_recycle=3600)


def init_schema(engine: Engine) -> None:
    """market 스키마 테이블을 만든다(이미 있으면 그대로 둔다)."""
    Base.metadata.create_all(engine)


def session_factory(engine: Engine) -> sessionmaker[Session]:
    return sessionmaker(bind=engine, expire_on_commit=False)
