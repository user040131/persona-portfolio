"""load_base 부품 단위 테스트 (합성 데이터). docs/03 §8.1, 01 FR-ETL."""
from __future__ import annotations

from datetime import date

import numpy as np
import pandas as pd
import pytest

from app.data import calendar
from app.domain.bond import bond_index, bond_returns, to_trading_days
from app.etl import transform
from app.etl.readers import normalize_code
from app.etl.validators import EtlValidationError, check_restoration, require_restoration_ok


@pytest.mark.parametrize("raw, expected", [(5930, "005930"), ("5930", "005930"), ("5930.0", "005930"),
                                           ("0126Z0", "0126Z0"), (" 000660 ", "000660")])
def test_종목코드_정규화(raw, expected) -> None:
    assert normalize_code(raw) == expected


def test_영업일은_주말과_공휴일을_뺀다() -> None:
    s = calendar.sessions("2026-08-14", "2026-08-19")
    # 8/15(토)·16(일)·17(월, 광복절 대체공휴일) 제외
    assert [d.date() for d in s] == [date(2026, 8, 14), date(2026, 8, 18), date(2026, 8, 19)]


def test_날짜_복원은_끝에서부터_붙인다() -> None:
    sess = pd.DatetimeIndex(["2026-08-13", "2026-08-14", "2026-08-18", "2026-08-19"])
    df = pd.DataFrame({"A": [1.0, 2.0, 3.0]})
    out = transform.restore_dates(df, sess)
    assert list(out.index) == list(sess[-3:])
    assert out["A"].iloc[-1] == 3.0


def test_영업일이_모자라면_중단() -> None:
    with pytest.raises(EtlValidationError):
        transform.restore_dates(pd.DataFrame({"A": [1.0, 2.0]}), pd.DatetimeIndex(["2026-08-19"]))


def _prices() -> pd.DataFrame:
    idx = pd.DatetimeIndex(["2026-08-18", "2026-08-19"])
    return pd.DataFrame({"삼성전자": [268500.0, 247500.0], "기아": [np.nan, 112300.0]}, index=idx)


def test_복원_검증_일치하면_통과() -> None:
    check = check_restoration(_prices(), _prices())
    assert (check.overlap_days, check.compared_cells, check.mismatches) == (2, 3, 0)
    require_restoration_ok(check)


def test_복원_검증_값이_하나라도_다르면_중단() -> None:
    other = _prices()
    other.iloc[1, 0] = 247501.0
    check = check_restoration(_prices(), other)
    assert check.mismatches == 1
    assert check.examples[0]["name"] == "삼성전자"
    with pytest.raises(EtlValidationError):
        require_restoration_ok(check)


def test_겹치는_날이_없으면_중단() -> None:
    shifted = _prices()
    shifted.index = shifted.index - pd.Timedelta(days=30)
    with pytest.raises(EtlValidationError):
        require_restoration_ok(check_restoration(_prices(), shifted))


def test_가격_행은_KOSPI를_분리하고_코드로_바꾸고_빈칸을_뺀다() -> None:
    df = _prices().assign(KOSPI=[6869.83, 6471.17], 모름=[1.0, 2.0])
    rows = transform.price_rows(df, {"삼성전자": "005930", "기아": "000270"})
    assert rows.unmapped_names == ["모름"]
    assert len(rows.prices) == 3                      # 기아 첫날 NaN 제외
    assert set(rows.prices["code"]) == {"005930", "000270"}
    assert rows.kospi["close"].tolist() == [6869.83, 6471.17]
    assert rows.kospi["index_code"].unique().tolist() == ["KOSPI"]


def test_재무_적용일은_다음_해_4월_1일이고_모르는_종목은_버린다() -> None:
    fin = pd.DataFrame({"code": ["005930", "999999"], "fiscal_year": [2025, 2025]})
    out, dropped = transform.financial_rows(fin, {"005930"})
    assert dropped == 1
    assert out["apply_date"].tolist() == [date(2026, 4, 1)]
    assert out["source"].tolist() == ["FILE"]


def test_종목_행_업종_그룹_미분류_관리종목() -> None:
    prices = pd.DataFrame({"code": ["005930", "000270", "000001"],
                           "date": [date(2010, 1, 4), date(2012, 3, 2), date(2020, 1, 2)]})
    sector = pd.DataFrame({"code": ["005930", "000270"], "sector": ["전기·전자", "보험"]})
    managed = pd.DataFrame({"code": ["000001"], "managed_date": [date(2026, 9, 8)]})
    out = transform.stock_rows({"삼성전자": "005930", "가짜보험": "000270", "관리": "000001", "가격없음": "123456"},
                               prices, sector, managed, {"금융": ["은행", "보험", "증권"]}).set_index("code")
    assert "123456" not in out.index                  # 가격이 없으면 종목에서 뺀다
    assert out.at["000270", "sector_group"] == "금융"
    assert out.at["005930", "sector_group"] == "전기·전자"
    assert out.at["000001", "sector"] == "미분류"
    assert bool(out.at["000001", "is_managed"]) and out.at["000001", "managed_date"] == date(2026, 9, 8)
    assert out.at["000270", "first_seen"] == date(2012, 3, 2)


def test_채권_수익률_T_BOND_01_02() -> None:
    r = bond_returns(pd.Series([3.00, 3.10]), duration=2.8)
    assert r.iloc[0] == 0.0
    assert r.iloc[1] == pytest.approx(-0.00268095, abs=1e-8)          # T-BOND-01: 0.03/252 − 2.8×0.001
    flat = bond_returns(pd.Series([3.00, 3.00]), duration=2.8)
    assert flat.iloc[1] == pytest.approx(0.000119048, abs=1e-9)       # T-BOND-02: 금리 그대로면 이자만


def test_채권_지수는_첫날_100() -> None:
    idx = bond_index(pd.Series([3.0, 3.0, 3.0]))
    assert idx.iloc[0] == 100.0
    assert idx.iloc[-1] == pytest.approx(100 * (1 + 0.03 / 252) ** 2)


def test_금리가_없는_영업일은_직전_값으로_채운다() -> None:
    rate = pd.Series([3.0, 3.2], index=pd.DatetimeIndex(["2026-08-13", "2026-08-19"]))
    days = pd.DatetimeIndex(["2026-08-14", "2026-08-18", "2026-08-19"])
    assert to_trading_days(rate, days).tolist() == [3.0, 3.0, 3.2]
