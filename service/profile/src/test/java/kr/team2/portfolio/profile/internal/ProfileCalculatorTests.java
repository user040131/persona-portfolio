package kr.team2.portfolio.profile.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import kr.team2.portfolio.common.BusinessException;
import kr.team2.portfolio.common.ErrorCode;
import kr.team2.portfolio.profile.api.Answers;
import kr.team2.portfolio.profile.api.Factor;
import kr.team2.portfolio.profile.api.Grade;
import kr.team2.portfolio.profile.api.Method;
import kr.team2.portfolio.profile.api.ProfileSettings;
import kr.team2.portfolio.profile.api.Survey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** docs/03 §8.7 T-SP-PRO-01~05. 값은 01 §5 파라미터 표와 같다. */
class ProfileCalculatorTests {

    static final Survey SURVEY = new Survey(List.of(5, 4, 2, 1, 0), 10, 25,
            Map.of(Grade.STABLE, new Survey.GradeInfo("안정추구형", ""),
                    Grade.NEUTRAL, new Survey.GradeInfo("위험중립형", ""),
                    Grade.AGGRESSIVE, new Survey.GradeInfo("공격추구형", "")),
            List.of(), List.of());

    static final ProfileMappingProperties.Mapping MAPPING = new ProfileMappingProperties.Mapping(
            List.of(0.0, -0.20, -0.10, -0.05, -0.03),
            List.of(0.0, 0.10, 0.30, 0.50),
            0.05,
            Map.of(Grade.STABLE, 0.40, Grade.NEUTRAL, 0.60, Grade.AGGRESSIVE, 0.90),
            List.of(0.0, 0.0, -0.05, -0.10),
            List.of(10, 15, 20, 15),
            Map.of(Grade.STABLE, 0.15, Grade.NEUTRAL, 0.20, Grade.AGGRESSIVE, 0.25),
            Map.of(Grade.STABLE, 0.30, Grade.NEUTRAL, 0.40, Grade.AGGRESSIVE, 0.50),
            Map.of(Grade.STABLE, Method.MIN_VARIANCE, Grade.NEUTRAL, Method.INVERSE_VOL, Grade.AGGRESSIVE, Method.EQUAL),
            Map.of(Grade.STABLE, 0.30, Grade.NEUTRAL, 0.10, Grade.AGGRESSIVE, 0.0),
            Map.of(Grade.STABLE, true, Grade.NEUTRAL, true, Grade.AGGRESSIVE, false),
            0.10,
            Map.of(
                    Grade.STABLE, Map.of(Factor.VALUE, 0.25, Factor.QUALITY, 0.30, Factor.MOMENTUM, 0.10, Factor.STABILITY, 0.25, Factor.DIVIDEND, 0.10),
                    Grade.NEUTRAL, Map.of(Factor.VALUE, 0.30, Factor.QUALITY, 0.30, Factor.MOMENTUM, 0.25, Factor.STABILITY, 0.15, Factor.DIVIDEND, 0.0),
                    Grade.AGGRESSIVE, Map.of(Factor.VALUE, 0.25, Factor.QUALITY, 0.25, Factor.MOMENTUM, 0.45, Factor.STABILITY, 0.05, Factor.DIVIDEND, 0.0)));

    static final List<String> SECTORS = List.of("전기·전자", "화학", "은행");

    final ProfileCalculator calc = new ProfileCalculator(SURVEY, MAPPING);

    static Answers answers(List<Integer> q, int a2, boolean a3, int a4, int a5, List<String> a6, List<String> a7) {
        return new Answers(q, a2, a3, a4, a5, a6, a7);
    }

    /** 01 FR-ONB 골든 프로필. */
    static final Answers GOLDEN = answers(List.of(3, 3, 3, 3, 3, 3, 3), 2, false, 3, 4, List.of("DIVIDEND"), List.of());

    @Test
    void 골든_프로필() {
        ProfileSettings s = calc.settings(GOLDEN, SECTORS);

        assertThat(s.grade()).isEqualTo(Grade.NEUTRAL);
        assertThat(s.lossLimit()).isEqualTo(-0.10);
        assertThat(s.minCash()).isEqualTo(0.15);
        assertThat(s.stockCap()).isEqualTo(0.55);
        assertThat(s.nStocks()).isEqualTo(15);
        assertThat(s.factorWeights()).containsExactly(
                Map.entry(Factor.VALUE, 0.2727), Map.entry(Factor.QUALITY, 0.2727), Map.entry(Factor.MOMENTUM, 0.2273),
                Map.entry(Factor.STABILITY, 0.1364), Map.entry(Factor.DIVIDEND, 0.0909));
        assertThat(s.volExcludeTopPct()).isEqualTo(0.10);
        assertThat(s.excludeOverheat()).isTrue();
        assertThat(s.stockLimit()).isEqualTo(0.20);
        assertThat(s.sectorLimit()).isEqualTo(0.40);
        assertThat(s.defaultMethod()).isEqualTo(Method.INVERSE_VOL);
        assertThat(s.excludedSectors()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"1, 35, AGGRESSIVE", "3, 14, NEUTRAL", "5, 0, STABLE"})
    void 모두_같은_번호로_답하면(int option, int expectedScore, Grade expectedGrade) {
        List<Integer> q = java.util.Collections.nCopies(7, option);
        assertThat(calc.totalScore(q)).isEqualTo(expectedScore);
        assertThat(calc.grade(expectedScore)).isEqualTo(expectedGrade);
    }

    @ParameterizedTest
    @CsvSource({"10, STABLE", "11, NEUTRAL", "24, NEUTRAL", "25, AGGRESSIVE"})
    void 등급_경계(int score, Grade grade) {
        assertThat(calc.grade(score)).isEqualTo(grade);
    }

    @Test
    void 투자_기간_1년_이내면_공격형도_안정형_필터를_쓴다() {
        // Q2만 5번(1년 이내), 나머지는 1번 → 총점 5×6 + 0 = 30, 공격추구형
        Answers a = answers(List.of(1, 5, 1, 1, 1, 1, 1), 1, true, 1, 4, List.of("ANY"), List.of());
        ProfileSettings s = calc.settings(a, SECTORS);

        assertThat(s.grade()).isEqualTo(Grade.AGGRESSIVE);
        assertThat(s.volExcludeTopPct()).isEqualTo(0.30);
        assertThat(s.excludeOverheat()).isTrue();
        assertThat(s.lossLimit()).as("Q7 1번 = 제한 없음").isNull();
        assertThat(s.stockCap()).isEqualTo(0.90);
        assertThat(s.defaultMethod()).isEqualTo(Method.EQUAL);
    }

    @Test
    void 상관없음이면_기본_가중치_그대로() {
        Answers a = answers(List.of(4, 4, 4, 4, 4, 4, 4), 1, true, 1, 1, List.of("ANY"), List.of("은행"));
        ProfileSettings s = calc.settings(a, SECTORS);

        assertThat(s.grade()).isEqualTo(Grade.STABLE);
        assertThat(s.factorWeights()).containsEntry(Factor.STABILITY, 0.25).containsEntry(Factor.DIVIDEND, 0.10);
        assertThat(s.minCash()).isEqualTo(0.0);
        assertThat(s.nStocks()).isEqualTo(10);
        assertThat(s.excludedSectors()).containsExactly("은행");
        assertThat(s.lossLimit()).isEqualTo(-0.05);
    }

    @Test
    void 상관없음과_다른_스타일을_같이_고르면_거절() {
        Answers a = answers(GOLDEN.q(), 2, false, 3, 4, List.of("ANY", "VALUE"), List.of());
        assertInvalid(a);
    }

    @Test
    void 문항_수가_틀리거나_범위_밖이면_거절() {
        assertInvalid(answers(List.of(3, 3, 3), 2, false, 3, 4, List.of("ANY"), List.of()));
        assertInvalid(answers(List.of(3, 3, 3, 3, 3, 3, 6), 2, false, 3, 4, List.of("ANY"), List.of()));
        assertInvalid(answers(GOLDEN.q(), 0, false, 3, 4, List.of("ANY"), List.of()));
    }

    @Test
    void 없는_스타일이나_없는_업종은_거절() {
        assertInvalid(answers(GOLDEN.q(), 2, false, 3, 4, List.of("GROWTH"), List.of()));
        assertInvalid(answers(GOLDEN.q(), 2, false, 3, 4, List.of("ANY"), List.of("우주항공")));
    }

    private void assertInvalid(Answers a) {
        assertThatThrownBy(() -> calc.settings(a, SECTORS))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.INVALID_SURVEY);
    }
}
