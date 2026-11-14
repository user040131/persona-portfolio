package kr.team2.portfolio.backtest.api;

/** 엔진 작업 상태 (docs/03 §3 I11). */
public enum BacktestStatus {
    QUEUED, RUNNING, DONE, FAILED
}
