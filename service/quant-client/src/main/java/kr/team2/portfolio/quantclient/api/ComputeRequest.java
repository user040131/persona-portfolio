package kr.team2.portfolio.quantclient.api;

import java.util.List;
import java.util.Map;

/**
 * 엔진 I6 요청 (docs/03 §3). JSON으로는 snake_case로 나간다.
 * quant-client는 profile 모듈을 모르므로 설정값을 자기 타입으로 받는다.
 */
public record ComputeRequest(Settings settings, long investAmount, Existing existing, Edits edits) {

    /** docs/03 §1.2 ProfileSettings. lossLimit null = 제한 없음. */
    public record Settings(String grade, Double lossLimit, double minCash, double stockCap, int nStocks,
                           Map<String, Double> factorWeights, double volExcludeTopPct, boolean excludeOverheat,
                           double stockLimit, double sectorLimit, String defaultMethod, List<String> excludedSectors) {
    }

    public record Holding(String code, int quantity) {
    }

    public record Existing(List<Holding> holdings, double bondUnits, long cash) {
    }

    /** safeRatio·method가 null이면 엔진이 자동으로 정한다. */
    public record Edits(List<String> removedCodes, Double safeRatio, String method) {
    }
}
