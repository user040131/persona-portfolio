package kr.team2.portfolio.profile.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.team2.portfolio.quantclient.api.ComputeRequest;

/**
 * 엔진에 보내는 프로필 설정값 (docs/03 §1.2 계약). 엔진은 설문 답을 모르고 이 값만 쓴다.
 *
 * @param lossLimit null이면 손실 한도 없음, 그 외 음수
 */
public record ProfileSettings(
        Grade grade,
        Double lossLimit,
        double minCash,
        double stockCap,
        int nStocks,
        Map<Factor, Double> factorWeights,
        double volExcludeTopPct,
        boolean excludeOverheat,
        double stockLimit,
        double sectorLimit,
        Method defaultMethod,
        List<String> excludedSectors) {

    /** 엔진에 보내는 모양 (docs/03 §1.2). 팩터는 5개 모두, 없는 팩터는 0으로 채운다. */
    public ComputeRequest.Settings toEngine() {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (Factor f : Factor.values()) {
            weights.put(f.name(), factorWeights.getOrDefault(f, 0.0));
        }
        return new ComputeRequest.Settings(grade.name(), lossLimit, minCash, stockCap, nStocks, weights,
                volExcludeTopPct, excludeOverheat, stockLimit, sectorLimit, defaultMethod.name(), excludedSectors);
    }
}
