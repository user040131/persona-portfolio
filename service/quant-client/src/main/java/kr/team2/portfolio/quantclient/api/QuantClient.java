package kr.team2.portfolio.quantclient.api;

/**
 * Python 퀀트 엔진 호출 창구. 내부 API(I1~I15, docs/03 §3)가 생길 때마다 메서드를 늘린다.
 * 엔진에 연결할 수 없으면 {@code QUANT_UNAVAILABLE} 예외를 던진다.
 */
public interface QuantClient {

    /** I1. 엔진 상태. */
    EngineHealth health();

    /** I4. 전 종목 목록. 보통은 직접 부르지 말고 {@link StockCatalog}를 쓴다. */
    StockList stocks();

    /**
     * I6. 추천 계산. 엔진 응답 본문(snake_case JSON)을 그대로 돌려준다.
     * recommendation 모듈이 이 문자열을 버전으로 저장하고, 화면에 보일 때 해석한다 (docs/03 §6.2).
     */
    String computeRecommendation(ComputeRequest request);

    /**
     * I8. 포트폴리오 평가 + 알림 조건 (docs/03 §1.10). 엔진 응답 본문(snake_case JSON)을 그대로 돌려준다.
     */
    String evaluatePortfolio(EvaluateRequest request);

    /**
     * I9. 리밸런싱 수량 (docs/03 §1.11). 엔진 응답 본문(snake_case JSON)을 그대로 돌려준다.
     * 평가금액이 0이면 {@code NOTHING_TO_REBALANCE}(409).
     */
    String rebalancePlan(RebalanceRequest request);

    /** I7. 이 설정으로 startYear부터 실험 D 로직을 운용한 과거 성과 카드 (엔진이 캐시). */
    String historyCard(ComputeRequest.Settings settings, int startYear);

    /** I10. 백테스트 작업을 만들고 작업 ID를 돌려준다 (엔진이 뒤에서 계산). */
    String createBacktest(BacktestRequest request);

    /** I11. 상태·진행률·결과 요약 (엔진 응답 JSON 그대로). 없는 작업이면 BACKTEST_NOT_FOUND. */
    String backtestStatus(String jobId);

    /** I12. NAV·낙폭. strategies는 쉼표로 이은 전략 키(null이면 전부), freq는 D·W·M. */
    String backtestNav(String jobId, String strategies, String freq);

    /** I13. 리밸런싱일별 편입 비중. date가 null이면 마지막 리밸런싱일. */
    String backtestHoldings(String jobId, String strategy, String date);

    /** I14. NAV CSV. */
    String backtestCsv(String jobId);
}
