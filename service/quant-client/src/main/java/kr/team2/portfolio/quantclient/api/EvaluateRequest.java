package kr.team2.portfolio.quantclient.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 엔진 I8 요청 (docs/03 §3). JSON으로는 snake_case로 나간다.
 *
 * @param targets 매수 완료 때 저장한 목표 비중 (종목코드·BOND·CASH). 매수 전이면 비어 있다
 */
public record EvaluateRequest(ComputeRequest.Settings settings, List<ComputeRequest.Holding> holdings,
                              double bondUnits, long cash, Map<String, Double> targets,
                              LocalDate purchasedAt, LocalDate holdingsChangedAt, Integer lastR6Year) {
}
