"""제공 파일 → market 스키마 적재 (docs/03 §1.13, 01 FR-ETL-01~09).

    python -m app.etl.load_base                          # data/raw에 파일이 이미 있을 때
    python -m app.etl.load_base --source-dir "../기반 문서"  # 원본 폴더에서 먼저 모은 뒤 적재

성공하면 etl_log에 SUCCESS 행이 생기고, 그 id가 새 data_version이 된다.
"""
from __future__ import annotations

import argparse
import json
import logging
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd
from sqlalchemy import Engine, update
from sqlalchemy.orm import Session

from app.config import Settings, get_settings
from app.data import calendar
from app.db.engine import init_schema, make_engine
from app.db.models import EtlLog
from app.domain.bond import bond_index, bond_returns, to_trading_days
from app.etl import readers, sources, transform, validators, writer

log = logging.getLogger("etl.load_base")
JOB = "load_base"


@dataclass
class Frames:
    """적재할 테이블별 행과 확인용 요약."""
    tables: dict[str, pd.DataFrame]
    summary: dict = field(default_factory=dict)


def build_frames(raw_dir: Path, config_dir: Path, settings: Settings) -> Frames:
    """원본을 읽어 테이블 행을 만든다. DB는 쓰지 않는다(테스트에서 그대로 쓸 수 있게)."""
    sources.require_raw(raw_dir)
    s: dict = {}

    sample = readers.read_sample(raw_dir / "price_sample.csv")
    long_raw = readers.read_long(raw_dir / "price_long.csv")
    last_day = sample.prices.index.max()

    # 1) 장기 가격에 영업일 붙이기 → 샘플과 대조 (FR-ETL-02·03)
    sess = calendar.sessions(calendar.CALENDAR_START, last_day)
    long_dated = transform.restore_dates(long_raw, sess)
    sample_sessions = transform.only_sessions(sample.prices, sess)
    check = validators.check_restoration(long_dated.drop(columns=[readers.KOSPI_COLUMN]), sample_sessions)
    validators.require_restoration_ok(check)
    s["restoration"] = {"first": str(long_dated.index[0].date()), "last": str(long_dated.index[-1].date()),
                        "rows": len(long_dated), "overlap_days": check.overlap_days,
                        "compared_cells": check.compared_cells, "mismatches": check.mismatches}

    # 2) 가격·지수 (FR-ETL-04·05)
    pr = transform.price_rows(long_dated, sample.code_by_name)
    if pr.unmapped_names:
        log.warning("종목코드를 찾지 못해 버린 종목 %d개: %s", len(pr.unmapped_names), pr.unmapped_names[:10])
    s["prices"] = {"rows": len(pr.prices), "stocks": int(pr.prices["code"].nunique()), "unmapped": pr.unmapped_names}

    # 3) 종목 (업종·관리종목·업종 그룹) (FR-ETL-07)
    groups = json.loads((config_dir / "sector_groups.json").read_text(encoding="utf-8"))["groups"]
    stocks = transform.stock_rows(sample.code_by_name, pr.prices, readers.read_sector(raw_dir / "sector.xlsx"),
                                  readers.read_managed(raw_dir / "managed.xlsx"), groups)
    s["stocks"] = {"rows": len(stocks), "unclassified": int((stocks["sector"] == transform.UNCLASSIFIED).sum()),
                   "managed": int(stocks["is_managed"].sum())}

    # 4) 재무 (FR-ETL-06)
    fin, dropped = transform.financial_rows(readers.read_financial(raw_dir / "financial.csv"),
                                            set(stocks["code"]), settings.fin_apply_month, settings.fin_apply_day)
    s["financial"] = {"rows": len(fin), "dropped_unknown_code": dropped,
                      "years": [int(fin["fiscal_year"].min()), int(fin["fiscal_year"].max())]}

    # 5) 금리 → 채권 지수 (FR-ETL-08)
    days = long_dated.index
    rate = to_trading_days(readers.read_ktb3y(raw_dir / "macro.xlsx"), days)
    if rate.isna().any():
        raise validators.EtlValidationError(f"금리가 없는 영업일이 있어요: {rate[rate.isna()].index[:5].tolist()}")
    ret = bond_returns(rate, settings.bond_duration)
    idx = bond_index(rate, settings.bond_duration)
    s["bond"] = {"first": float(idx.iloc[0]), "last": float(idx.iloc[-1])}

    d = days.date
    tables = {
        "trading_day": pd.DataFrame({"date": d}),
        "stock": stocks,
        "price_daily": pr.prices,
        "index_daily": pr.kospi,
        "rate_daily": pd.DataFrame({"series": "KTB3Y", "date": d, "value": rate.to_numpy()}),
        "bond_index_daily": pd.DataFrame({"date": d, "ret": ret.to_numpy(), "idx": idx.to_numpy()}),
        "financial_annual": fin,
        "price_flag": pd.DataFrame(columns=["code", "date", "change_ratio", "checked"]),
    }
    return Frames(tables, s)


def run(settings: Settings, engine: Engine | None = None, source_dir: Path | None = None) -> int:
    """적재를 실행하고 etl_log id(새 data_version)를 돌려준다. 실패하면 FAILED로 남기고 예외를 다시 던진다."""
    engine = engine or make_engine(settings.database_url)
    init_schema(engine)
    if source_dir is not None:
        made = sources.prepare_raw(source_dir, settings.raw_dir)
        if made:
            log.info("원본 파일을 모았어요: %s", made)

    now = lambda: datetime.now(timezone.utc).replace(tzinfo=None)  # noqa: E731  (UTC로 저장)
    with Session(engine) as db:
        entry = EtlLog(job=JOB, started_at=now(), status="RUNNING")
        db.add(entry)
        db.commit()
        etl_id = entry.id

    started = time.perf_counter()
    try:
        frames = build_frames(settings.raw_dir, settings.config_dir, settings)
        read_ms = int((time.perf_counter() - started) * 1000)
        steps = [{"table": "(read+transform)", "rows": None, "elapsed_ms": read_ms}]
        steps += writer.replace_tables(engine, frames.tables)
        detail = {"steps": steps, "summary": frames.summary}
        with Session(engine) as db:
            db.execute(update(EtlLog).where(EtlLog.id == etl_id)
                       .values(status="SUCCESS", finished_at=now(), steps=detail))
            db.commit()
        log.info("load_base 성공 (etl_id=%d, %.1fs): %s", etl_id, time.perf_counter() - started,
                 json.dumps(frames.summary, ensure_ascii=False, default=str))
        return etl_id
    except Exception as e:
        with Session(engine) as db:
            db.execute(update(EtlLog).where(EtlLog.id == etl_id)
                       .values(status="FAILED", finished_at=now(), message=f"{type(e).__name__}: {e}"[:4000]))
            db.commit()
        log.error("load_base 실패 (etl_id=%d): %s", etl_id, e)
        raise


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="제공 파일을 market 스키마에 적재한다")
    p.add_argument("--source-dir", type=Path, help="원본 폴더(기반 문서). 주면 data/raw로 먼저 모은다")
    args = p.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    try:
        run(get_settings(), source_dir=args.source_dir)
        return 0
    except (sources.EtlInputError, validators.EtlValidationError) as e:
        print(f"[load_base] {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
