package kr.team2.portfolio.profile.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.team2.portfolio.common.BusinessException;
import kr.team2.portfolio.common.ErrorCode;
import kr.team2.portfolio.profile.api.Answers;
import kr.team2.portfolio.profile.api.Factor;
import kr.team2.portfolio.profile.api.Grade;
import kr.team2.portfolio.profile.api.ProfileSettings;
import kr.team2.portfolio.profile.api.Survey;

/**
 * 설문 답 → 총점·등급·프로필 설정값 (docs/03 §6.1). 상태 없는 순수 계산.
 */
final class ProfileCalculator {

    static final String ANY = "ANY";
    private static final int Q2_SHORT_HORIZON = 5;   // "1년 이내"

    private final Survey survey;
    private final ProfileMappingProperties.Mapping m;

    ProfileCalculator(Survey survey, ProfileMappingProperties.Mapping mapping) {
        this.survey = survey;
        this.m = mapping;
    }

    int totalScore(List<Integer> q) {
        return q.stream().mapToInt(x -> survey.optionScores().get(x - 1)).sum();
    }

    Grade grade(int score) {
        if (score <= survey.stableMax()) {
            return Grade.STABLE;
        }
        return score >= survey.aggressiveMin() ? Grade.AGGRESSIVE : Grade.NEUTRAL;
    }

    /** 검증 후 설정값 계산. 업종 목록은 A7 검증에 쓴다. */
    ProfileSettings settings(Answers a, Collection<String> knownSectors) {
        validate(a, knownSectors);
        Grade g = grade(totalScore(a.q()));
        Grade filterGrade = a.q().get(1) == Q2_SHORT_HORIZON ? Grade.STABLE : g;   // 결정 6-5
        double lossLimit = m.lossLimitByQ7().get(a.q().get(6) - 1);
        return new ProfileSettings(
                g,
                lossLimit == 0 ? null : lossLimit,
                round(m.cashByA2().get(a.a2() - 1) + (a.a3() ? 0 : m.cashNoEmergencyAdd()), 2),
                round(m.stockCap().get(g) + m.stockCapA4Adj().get(a.a4() - 1), 2),
                m.nStocksByA5().get(a.a5() - 1),
                factorWeights(g, a.a6()),
                m.volExcludeTopPct().get(filterGrade),
                m.excludeOverheat().get(filterGrade),
                m.stockLimit().get(g),
                m.sectorLimit().get(g),
                m.defaultMethod().get(g),
                List.copyOf(new java.util.LinkedHashSet<>(a.a7())));
    }

    /** 기본 가중치에 A6에서 고른 스타일마다 a6Bonus를 더하고 합 1로 맞춘다 (결정 6-4). */
    Map<Factor, Double> factorWeights(Grade g, List<String> a6) {
        EnumMap<Factor, Double> w = new EnumMap<>(Factor.class);
        for (Factor f : Factor.values()) {
            w.put(f, m.factorWeights().get(g).getOrDefault(f, 0.0));
        }
        if (!a6.equals(List.of(ANY))) {
            a6.forEach(f -> w.merge(Factor.valueOf(f), m.a6Bonus(), Double::sum));
        }
        double sum = w.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<Factor, Double> out = new LinkedHashMap<>();
        w.forEach((f, v) -> out.put(f, round(v / sum, 4)));
        return out;
    }

    /** 01 FR-ONB-04·06·07, docs/03 §6.1 검증. 문제가 있으면 INVALID_SURVEY(422). */
    void validate(Answers a, Collection<String> knownSectors) {
        List<String> problems = new ArrayList<>();
        if (a.q() == null || a.q().size() != 7) {
            problems.add("q: 7개여야 해요");
        } else if (a.q().stream().anyMatch(x -> x == null || x < 1 || x > 5)) {
            problems.add("q: 1~5 사이여야 해요");
        }
        if (a.a2() < 1 || a.a2() > 4) problems.add("a2: 1~4");
        if (a.a4() < 1 || a.a4() > 4) problems.add("a4: 1~4");
        if (a.a5() < 1 || a.a5() > 4) problems.add("a5: 1~4");
        validateA6(a.a6(), problems);
        if (a.a7() == null) {
            problems.add("a7: 비어 있으면 []로 보내 주세요");
        } else {
            Set<String> sectors = new HashSet<>(knownSectors);
            a.a7().stream().filter(s -> !sectors.contains(s)).forEach(s -> problems.add("a7: 없는 업종 " + s));
        }
        if (!problems.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_SURVEY, Map.of("problems", problems));
        }
    }

    private static void validateA6(List<String> a6, List<String> problems) {
        if (a6 == null || a6.isEmpty()) {
            problems.add("a6: 하나 이상 골라 주세요");
            return;
        }
        if (a6.contains(ANY) && a6.size() > 1) {
            problems.add("a6: '상관없음'은 다른 스타일과 같이 고를 수 없어요");
            return;
        }
        if (new HashSet<>(a6).size() != a6.size()) {
            problems.add("a6: 같은 스타일이 두 번 있어요");
        }
        for (String f : a6) {
            if (ANY.equals(f)) continue;
            try {
                Factor.valueOf(f);
            } catch (IllegalArgumentException e) {
                problems.add("a6: 없는 스타일 " + f);
            }
        }
    }

    static double round(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}
