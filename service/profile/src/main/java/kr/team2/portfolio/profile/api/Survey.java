package kr.team2.portfolio.profile.api;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 설문 정의 ({@code survey.yml}, docs/03 §5.1). 화면 그리기와 등급 라벨에 쓴다.
 *
 * @param optionScores 선택지 1~5번의 점수 (5·4·2·1·0)
 */
@ConfigurationProperties(prefix = "survey")
public record Survey(
        List<Integer> optionScores,
        int stableMax,
        int aggressiveMin,
        Map<Grade, GradeInfo> grades,
        List<Question> questions,
        List<Question> extras) {

    public record GradeInfo(String label, String description) {
    }

    /**
     * @param options      번호형 선택지 (1번부터)
     * @param keyedOptions 키가 있는 선택지 (a6)
     * @param optionsFrom  선택지를 다른 곳에서 가져옴 (a7: "sectors")
     */
    public record Question(String id, String text,
                           @DefaultValue List<String> options,
                           boolean multi,
                           @DefaultValue List<KeyedOption> keyedOptions,
                           String optionsFrom) {
    }

    public record KeyedOption(String key, String text) {
    }

    public String label(Grade grade) {
        return grades.get(grade).label();
    }

    public String description(Grade grade) {
        return grades.get(grade).description();
    }
}
