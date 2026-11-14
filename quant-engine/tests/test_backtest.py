"""백테스트 (docs/03 §1.12, §8.6 T-BT-*·T-MET-*)."""
from __future__ import annotations

import pandas as pd
import pytest

from app.config import Settings
from app.domain import backtest_engine as bt
from app.domain import metrics
from app.services import backtest_service
from app.services.backtest_service import Params, Profile


def test_T_BT_01_리밸런싱일은_매년_4월_1일_이후_첫_영업일(real_market) -> None:  # noqa: ANN001
    dates = bt.rebalance_dates(real_market.trading_days, 2015, pd.Timestamp("2026-08-19"))
    assert [d.date().isoformat() for d in dates] == [
        "2015-04-01", "2016-04-01", "2017-04-03", "2018-04-02", "2019-04-01", "2020-04-01",
        "2021-04-01", "2022-04-01", "2023-04-03", "2024-04-01", "2025-04-01", "2026-04-01"]


def test_T_BT_02_리밸런싱_비용() -> None:
    days = pd.bdate_range("2026-01-05", periods=3)
    r = pd.DataFrame({"A": [0.0, 0.10, 0.0], "B": [0.0, 0.0, 0.0]}, index=days)
    w = pd.Series({"A": 0.5, "B": 0.5})
    nav = bt.run_nav({days[0]: w, days[2]: w}, r, cost=0.01)
    assert list(nav.round(6)) == [100.0, 105.0, 104.95]          # 첫 매수는 비용 없음


def test_T_BT_03_월말_점검이_참이면_그날_재조정하고_비용을_낸다() -> None:
    days = pd.bdate_range("2026-01-28", "2026-02-03")                # 1월 말(30일)이 끼어 있다. 마지막 날(2/3)은 월말 아님
    r = pd.DataFrame({"A": 0.0, "B": 0.0}, index=days)
    r.loc[days[1], "A"] = 0.10
    w = pd.Series({"A": 0.5, "B": 0.5})
    calls = []
    nav = bt.run_nav({days[0]: w}, r, cost=0.01, band_check=lambda cur, tgt: calls.append(cur) or True)
    assert len(calls) == 1                                            # 월말 하루만 본다
    assert nav.iloc[-1] == pytest.approx(105 - 0.05)
    no_check = bt.run_nav({days[0]: w}, r, cost=0.01, band_check=lambda cur, tgt: False)
    assert no_check.iloc[-1] == pytest.approx(105)


def test_월말_점검_R1_R4() -> None:
    check = bt.profile_band_check({"A": "x", "B": "y", "C": "z"}, stock_limit=0.5, sector_limit=0.6, band=0.05)
    w = pd.Series({"A": 0.2, "B": 0.2, "C": 0.2, "BOND": 0.3, "CASH": 0.1})
    assert not check(w, w)
    assert check(pd.Series({"A": 0.42, "B": 0.1, "C": 0.1, "BOND": 0.28, "CASH": 0.1}), w)   # R1 + R3
    assert check(pd.Series({"A": 0.24, "B": 0.22, "C": 0.22, "BOND": 0.25, "CASH": 0.07}), w)  # R4·R3


def test_T_MET_01_최대_낙폭() -> None:
    nav = pd.Series([100, 120, 90, 130], index=pd.bdate_range("2026-01-05", periods=4), dtype=float)
    assert metrics.max_drawdown(nav) == pytest.approx(-0.25)


def test_T_MET_02_연환산_수익률() -> None:
    nav = pd.Series([100.0, 121.0], index=pd.to_datetime(["2020-01-01", "2022-01-01"]))
    assert metrics.cagr(nav) == pytest.approx(0.099928, abs=1e-6)


def test_T_MET_03_한도_위반_날수와_구간() -> None:
    # dd [0, −0.12, −0.11, −0.05, −0.13]
    nav = pd.Series([100, 88, 89, 95, 87], index=pd.bdate_range("2026-01-05", periods=5), dtype=float)
    assert metrics.breaches(nav, -0.10) == (3, 2)


def test_연도별_수익률은_첫_해는_첫날_대비() -> None:
    nav = pd.Series([100.0, 110.0, 99.0], index=pd.to_datetime(["2025-06-02", "2025-12-30", "2026-03-02"]))
    assert metrics.yearly(nav) == pytest.approx({2025: 0.10, 2026: -0.10})


@pytest.fixture(scope="module")
def short_run(real_market):  # noqa: ANN001, ANN201
    from tests.test_reco_golden import GOLDEN
    p = Params(start_year=2024, experiments=("A", "B", "C", "D"), profiles=(Profile("NEUTRAL_REP", GOLDEN),))
    return backtest_service.run(real_market, Settings(), p)


def test_실제_데이터로_A_B_C_D를_돌린다(short_run) -> None:  # noqa: ANN001
    r = short_run
    assert r["rebalance_dates"] == ["2024-04-01", "2025-04-01", "2026-04-01"]
    keys = {s["strategy"] for s in r["strategies"]}
    assert keys == {"VALUE", "QUALITY", "MOMENTUM", "STABILITY", "DIVIDEND", "MULTI_NEUTRAL", "MULTI_GLOBAL",
                    "MULTI_EW", "MULTI_IV", "MULTI_MV", "KOSPI", "NEUTRAL_REP", "BENCH_NEUTRAL_REP"}
    nav = r["navs"]["MULTI_EW"]
    assert nav.iloc[0] == pytest.approx(100.0) and nav.index[-1] == pd.Timestamp("2026-08-19")
    assert r["navs"]["MULTI_NEUTRAL"].equals(nav)
    d = next(s for s in r["strategies"] if s["strategy"] == "NEUTRAL_REP")
    assert "limit_breach_days" in d["metrics"]
    # 실험 D 목표: 주식·채권·현금 합 1, 현금은 최소 비중 이상
    for w in r["holdings"]["NEUTRAL_REP"].values():
        assert w.sum() == pytest.approx(1.0, abs=0.01) and w.get("CASH", 0) >= 0.15 - 1e-9
    assert len(r["holdings"]["MULTI_EW"][pd.Timestamp("2024-04-01")]) == 15


def test_T_BT_04_같은_파라미터면_같은_결과(real_market, short_run) -> None:  # noqa: ANN001
    from tests.test_reco_golden import GOLDEN
    again = backtest_service.run(real_market, Settings(), Params(start_year=2024, experiments=("A", "B", "C", "D"),
                                                                 profiles=(Profile("NEUTRAL_REP", GOLDEN),)))
    assert [s["metrics"] for s in again["strategies"]] == [s["metrics"] for s in short_run["strategies"]]


def test_FR_BT_09_비용이_없으면_NAV가_크거나_같다(real_market, short_run) -> None:  # noqa: ANN001
    free = backtest_service.run(real_market, Settings(), Params(start_year=2024, experiments=("C",),
                                                                trading_cost_rate=0.0))
    assert free["navs"]["MULTI_EW"].iloc[-1] >= short_run["navs"]["MULTI_EW"].iloc[-1]


def test_한_번_계산한_점수로_매긴_순위는_scoring_score와_같다(real_market) -> None:  # noqa: ANN001
    from app.domain import scoring
    s = Settings()
    t = real_market.to_trading_day(pd.Timestamp("2020-04-01"))
    inp = backtest_service.inputs_at(real_market, s, t)
    w = Params().multi_weights
    for neutral in (True, False):
        expected = scoring.score(inp.ind.loc[inp.common.universe], inp.snap.stocks["sector_group"], w,
                                 neutral=neutral, min_size=s.sector_min_size).index[:15]
        assert backtest_service._top(real_market, inp, w, neutral, s) == list(expected)


# ---------------------------------------------------------------- API (I7, I10~I14)

def _settings_json() -> dict:
    from tests.test_reco_golden import GOLDEN
    return {**GOLDEN.__dict__, "excluded_sectors": list(GOLDEN.excluded_sectors)}


def test_I10_I14_작업을_만들고_결과를_읽는다(client, real_market) -> None:  # noqa: ANN001
    client.app.state.market = real_market
    r = client.post("/internal/v1/backtests", json={"start_year": 2025, "experiments": ["C", "D"],
                                                    "profiles": [{"key": "NEUTRAL_REP", "settings": _settings_json()}]})
    assert r.status_code == 202, r.text
    job = r.json()["job_id"]
    # 작업자 스레드는 하나뿐이라, 빈 작업을 넣고 기다리면 앞의 백테스트가 끝난 뒤다.
    # (단위 테스트의 SQLite는 연결 하나를 같이 써서, 계산 중에 상태를 읽으면 쓰기와 부딪힐 수 있다)
    from app.services import backtest_jobs
    backtest_jobs._worker.submit(lambda: None).result(timeout=120)
    st = client.get(f"/internal/v1/backtests/{job}").json()
    assert st["status"] == "DONE", st
    assert st["progress"] == 1.0 and st["result"]["rebalance_dates"] == ["2025-04-01", "2026-04-01"]
    assert {s["strategy"] for s in st["result"]["strategies"]} == {"MULTI_EW", "KOSPI", "NEUTRAL_REP",
                                                                   "BENCH_NEUTRAL_REP"}
    assert st["result"]["experiments"]["D"] == ["NEUTRAL_REP", "BENCH_NEUTRAL_REP"]

    weekly = client.get(f"/internal/v1/backtests/{job}/nav", params={"freq": "W"})   # 2025 추석 연휴 주 포함
    assert weekly.status_code == 200, weekly.text
    nav = client.get(f"/internal/v1/backtests/{job}/nav", params={"strategies": "KOSPI,MULTI_EW", "freq": "M"}).json()
    assert set(nav["strategies"]) == {"KOSPI", "MULTI_EW"} and nav["dates"][-1] == "2026-08-19"   # 실제 마지막 영업일
    assert nav["strategies"]["KOSPI"]["nav"][0] > 0 and max(nav["strategies"]["KOSPI"]["drawdown"]) <= 0

    h = client.get(f"/internal/v1/backtests/{job}/holdings", params={"strategy": "MULTI_EW", "date": "2025-12-31"})
    assert h.json()["date"] == "2025-04-01" and len(h.json()["holdings"]) == 15
    assert all(isinstance(x["weight"], float) for x in h.json()["holdings"])

    csv_text = client.get(f"/internal/v1/backtests/{job}/export.csv").text
    assert csv_text.splitlines()[0] == "date,BENCH_NEUTRAL_REP,KOSPI,MULTI_EW,NEUTRAL_REP"

    assert client.get("/internal/v1/backtests/nope").json()["error"]["code"] == "BACKTEST_NOT_FOUND"
    bad = client.post("/internal/v1/backtests", json={"experiments": ["D"]})
    assert bad.status_code == 422


def test_I7_과거_성과_카드는_같은_설정이면_캐시(client, real_market) -> None:  # noqa: ANN001
    client.app.state.market = real_market
    body = {"settings": _settings_json(), "start_year": 2024}
    first = client.post("/internal/v1/history-card", json=body).json()
    assert first["cached"] is False and first["start"] == "2024-04-01" and first["end"] == "2026-08-19"
    assert first["max_drawdown"] <= 0 and first["worst_year"]["year"] in (2024, 2025, 2026)
    again = client.post("/internal/v1/history-card", json=body).json()
    assert again["cached"] is True and again["cumulative_return"] == first["cumulative_return"]


def test_1년_내내_가격이_그대로인_종목은_후보에서_빠진다(real_market) -> None:  # noqa: ANN001
    """2018-04-02: 011810은 1년 동안 종가가 변하지 않아(거래정지) 변동성이 0이었다 → 변동성 역수 비중이 NaN이 되던 버그."""
    inp = backtest_service.inputs_at(real_market, Settings(), real_market.to_trading_day(pd.Timestamp("2018-04-02")))
    assert "011810" not in inp.common.universe
    assert inp.common.reasons["011810"] == "INSUFFICIENT_HISTORY"
