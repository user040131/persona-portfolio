"""시장 데이터 메모리 캐시 (docs/03 §1.4).

기동할 때 market 스키마를 한 번 읽어 넓은 형태(날짜 × 종목코드)로 들고 있는다. 읽기 전용이다.
배치를 다시 돌린 뒤에는 /internal/v1/admin/reload 로 다시 읽는다.

미래 정보 차단은 snapshot() 한 곳에서 보장한다. 계산 함수는 Snapshot만 받는다.
"""
from __future__ import annotations

import logging
import pickle
import time
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path

import pandas as pd
from sqlalchemy import Engine, text

from app.data.status import read_status
from app.domain.types import Snapshot  # noqa: F401  (예전 import 경로 호환)

log = logging.getLogger(__name__)


def expected_fiscal_year(t: pd.Timestamp, month: int = 4, day: int = 1) -> int:
    """기준일에 쓸 회계연도: 4월 1일 이후면 전년, 이전이면 전전년 (01 FR-SCR-03)."""
    return t.year - 1 if (t.month, t.day) >= (month, day) else t.year - 2


@dataclass
class MarketData:
    close: pd.DataFrame            # index=날짜, columns=종목코드 (코드 오름차순)
    returns: pd.DataFrame
    kospi: pd.Series
    kospi_ret: pd.Series
    bond_idx: pd.Series
    bond_ret: pd.Series
    financial: pd.DataFrame        # code, fiscal_year, apply_date(Timestamp), 값들, source
    stocks: pd.DataFrame           # index=code
    data_version: int
    fin_apply: tuple[int, int] = (4, 1)
    _cache: dict = field(default_factory=dict, repr=False)

    @property
    def trading_days(self) -> pd.DatetimeIndex:
        return self.close.index

    @property
    def as_of(self) -> pd.Timestamp:
        return self.close.index[-1]

    # ---------------------------------------------------------------- 만들기
    @classmethod
    def from_frames(cls, tables: dict[str, pd.DataFrame], data_version: int,
                    fin_apply: tuple[int, int] = (4, 1)) -> MarketData:
        """load_base가 만든 테이블 행(또는 DB에서 읽은 행)으로 만든다."""
        days = pd.DatetimeIndex(pd.to_datetime(tables["trading_day"]["date"])).sort_values()
        p = tables["price_daily"]
        close = (p.assign(date=pd.to_datetime(p["date"]))
                 .pivot(index="date", columns="code", values="close")
                 .reindex(days).sort_index(axis=1))
        close.index.name, close.columns.name = "date", "code"

        k = tables["index_daily"]
        k = k[k["index_code"] == "KOSPI"]
        # 원본에서 KOSPI가 빈 날(휴장일 2일)은 전날 값으로 채운다 → 그날 수익률 0, 다음 날 수익률은 정상
        kospi = pd.Series(k["close"].to_numpy(), index=pd.to_datetime(k["date"])).reindex(days).ffill()

        b = tables["bond_index_daily"]
        b_idx = pd.to_datetime(b["date"])
        bond_idx = pd.Series(b["idx"].to_numpy(), index=b_idx).reindex(days)
        bond_ret = pd.Series(b["ret"].to_numpy(), index=b_idx).reindex(days).fillna(0.0)

        fin = tables["financial_annual"].copy()
        fin["apply_date"] = pd.to_datetime(fin["apply_date"])
        stocks = tables["stock"].set_index("code").sort_index()
        stocks["is_managed"] = stocks["is_managed"].astype(bool)

        return cls(
            close=close,
            returns=close.pct_change(fill_method=None),
            kospi=kospi,
            kospi_ret=kospi.pct_change(fill_method=None).fillna(0.0),
            bond_idx=bond_idx,
            bond_ret=bond_ret,
            financial=fin,
            stocks=stocks,
            data_version=data_version,
            fin_apply=fin_apply,
        )

    @classmethod
    def from_db(cls, engine: Engine, cache_dir: Path | None = None,
                fin_apply: tuple[int, int] = (4, 1)) -> MarketData | None:
        """market 스키마에서 읽는다. 데이터가 없으면 None.

        cache_dir를 주면 data_version별 피클을 남겨 다음 기동을 빠르게 한다(가격 292만 행 읽기 생략).
        """
        from sqlalchemy.orm import Session

        with Session(engine) as db:
            status = read_status(db)
        if not status.data_loaded or status.data_version is None:
            return None
        version = status.data_version

        if cache_dir is not None:
            cached = cache_dir / f"market_v{version}.pkl"
            if cached.exists():
                started = time.perf_counter()
                with open(cached, "rb") as f:
                    md: MarketData = pickle.load(f)
                log.info("시장 데이터 캐시 사용 v%d (%.1fs)", version, time.perf_counter() - started)
                return md

        started = time.perf_counter()
        tables = {}
        with engine.connect() as conn:
            for name in ("trading_day", "stock", "price_daily", "index_daily", "bond_index_daily", "financial_annual"):
                tables[name] = pd.read_sql(text(f"SELECT * FROM {name}"), conn)
        md = cls.from_frames(tables, version, fin_apply)
        log.info("시장 데이터 적재 v%d: %d일 × %d종목 (%.1fs)", version, len(md.close), md.close.shape[1],
                 time.perf_counter() - started)

        if cache_dir is not None:
            cache_dir.mkdir(parents=True, exist_ok=True)
            for old in cache_dir.glob("market_v*.pkl"):
                old.unlink(missing_ok=True)
            with open(cache_dir / f"market_v{version}.pkl", "wb") as f:
                pickle.dump(md, f, protocol=pickle.HIGHEST_PROTOCOL)
        return md

    # ---------------------------------------------------------------- 조회
    def to_trading_day(self, d: date | pd.Timestamp) -> pd.Timestamp:
        """d 이하 마지막 영업일."""
        i = self.trading_days.searchsorted(pd.Timestamp(d), side="right") - 1
        if i < 0:
            raise ValueError(f"{d} 이전 영업일이 없어요")
        return self.trading_days[i]

    def snapshot(self, t: date | pd.Timestamp | None = None) -> Snapshot:
        """기준일 t에 알 수 있는 데이터만 잘라 준다 (NFR-03)."""
        t = self.as_of if t is None else self.to_trading_day(t)
        key = ("snapshot", t)
        if key not in self._cache:
            fy = expected_fiscal_year(t, *self.fin_apply)
            f = self.financial
            fin = f[(f["fiscal_year"] == fy) & (f["apply_date"] <= t)].drop_duplicates("code").set_index("code")
            self._cache[key] = Snapshot(t, self.close.loc[:t], self.returns.loc[:t], self.kospi_ret.loc[:t],
                                        self.bond_ret.loc[:t], fin, self.stocks)
        return self._cache[key]

    def memo(self, key: tuple, factory):  # noqa: ANN001, ANN201
        """같은 데이터 버전 안에서 한 번만 계산할 값 (예: 최신일 상장폐지 위험 판정)."""
        if key not in self._cache:
            self._cache[key] = factory()
        return self._cache[key]

    def __getstate__(self):  # noqa: ANN204
        state = self.__dict__.copy()
        state["_cache"] = {}          # 계산 캐시는 피클에 넣지 않는다
        return state
