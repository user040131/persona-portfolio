package kr.team2.portfolio.profile.internal;

import java.util.List;
import java.util.Map;
import kr.team2.portfolio.profile.api.Factor;
import kr.team2.portfolio.profile.api.Grade;
import kr.team2.portfolio.profile.api.Method;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 {@code portfolio.*} — 설문 답을 설정값으로 바꾸는 숫자들 (01 §5 소유=S, docs/03 §5.4).
 *
 * @param minInvestAmount 최소 투자 금액 (원)
 */
@ConfigurationProperties(prefix = "portfolio")
record ProfileMappingProperties(long minInvestAmount, Mapping profile) {

    /**
     * @param lossLimitByQ7 Q7 1~5번 → 손실 한도. <b>0은 "제한 없음"</b>
     *                      (YAML 목록의 null은 바인딩 때 사라질 수 있어 0으로 표시한다)
     */
    record Mapping(
            List<Double> lossLimitByQ7,
            List<Double> cashByA2,
            double cashNoEmergencyAdd,
            Map<Grade, Double> stockCap,
            List<Double> stockCapA4Adj,
            List<Integer> nStocksByA5,
            Map<Grade, Double> stockLimit,
            Map<Grade, Double> sectorLimit,
            Map<Grade, Method> defaultMethod,
            Map<Grade, Double> volExcludeTopPct,
            Map<Grade, Boolean> excludeOverheat,
            double a6Bonus,
            Map<Grade, Map<Factor, Double>> factorWeights) {
    }
}
