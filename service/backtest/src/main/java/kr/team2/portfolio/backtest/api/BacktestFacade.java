package kr.team2.portfolio.backtest.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 백테스트 작업 (docs/03 §6.6, 01 FR-BT-*). 계산은 엔진이 하고, 여기서는 작업 소유권만 지킨다.
 * 결과(I11~I14)는 엔진 응답 JSON(snake_case)을 그대로 돌려준다.
 */
public interface BacktestFacade {

    /** 비운 값은 기본값 (시작 2015, 끝 최신, 비용 0.003, 실험 A~D). */
    record CreateCommand(Integer startYear, LocalDate endDate, Double tradingCostRate, List<String> experiments) {
    }

    record JobView(String jobId, BacktestStatus status, int startYear, LocalDate endDate, double tradingCostRate,
                   List<String> experiments, LocalDateTime createdAt) {
    }

    JobView create(long userId, CreateCommand command);

    /** 내 작업, 최근 것부터. 상태는 마지막으로 확인한 값이다. */
    List<JobView> list(long userId);

    /** I11 + 상태 기록. 남의 작업이면 BACKTEST_NOT_FOUND. */
    String status(long userId, String jobId);

    String nav(long userId, String jobId, String strategies, String freq);

    String holdings(long userId, String jobId, String strategy, String date);

    String csv(long userId, String jobId);
}
