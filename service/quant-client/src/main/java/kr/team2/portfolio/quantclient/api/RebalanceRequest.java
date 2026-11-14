package kr.team2.portfolio.quantclient.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 엔진 I9 요청 = I8 요청 + 알림 종류·대상 (docs/03 §3). JSON으로는 snake_case로 나간다. */
public record RebalanceRequest(ComputeRequest.Settings settings, List<ComputeRequest.Holding> holdings,
                               double bondUnits, long cash, Map<String, Double> targets,
                               LocalDate purchasedAt, LocalDate holdingsChangedAt, Integer lastR6Year,
                               String alertType, String alertTarget) {

    public static RebalanceRequest of(EvaluateRequest e, String alertType, String alertTarget) {
        return new RebalanceRequest(e.settings(), e.holdings(), e.bondUnits(), e.cash(), e.targets(),
                e.purchasedAt(), e.holdingsChangedAt(), e.lastR6Year(), alertType, alertTarget);
    }
}
