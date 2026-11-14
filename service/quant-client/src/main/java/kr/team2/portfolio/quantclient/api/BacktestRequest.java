package kr.team2.portfolio.quantclient.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 엔진 I10 요청 (docs/03 §3). JSON으로는 snake_case로 나간다. */
public record BacktestRequest(int startYear, LocalDate endDate, double tradingCostRate, List<String> experiments,
                              Map<String, Double> multiWeights, List<Profile> profiles) {

    public record Profile(String key, ComputeRequest.Settings settings) {
    }
}
