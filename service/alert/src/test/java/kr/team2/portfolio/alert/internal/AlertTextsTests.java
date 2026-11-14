package kr.team2.portfolio.alert.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import kr.team2.portfolio.alert.api.AlertType;
import kr.team2.portfolio.common.Texts;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 알림 문구 (docs/03 §6.3 알림 문구). */
class AlertTextsTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final AlertTexts texts = new AlertTexts(Texts.load("messages_alerts.properties"),
            code -> Map.of("005930", "삼성전자", "000040", "KR모터스").getOrDefault(code, code));

    private String text(AlertType type, String target, String detail) {
        return texts.text(type, target, JSON.readTree(detail));
    }

    @Test
    void 알림_종류별_문구() {
        assertThat(text(AlertType.R1, "005930", "{\"weight\": 0.5282, \"limit\": 0.2}"))
                .isEqualTo("삼성전자이(가) 주식의 53%예요 (한도 20%)");
        assertThat(text(AlertType.R2, "전기·전자", "{\"weight\": 0.46, \"limit\": 0.4}"))
                .isEqualTo("전기·전자 업종이 주식의 46%예요 (한도 40%)");
        assertThat(text(AlertType.R3, "BOND", "{\"current\": 0.24, \"target\": 0.3}"))
                .isEqualTo("채권 비중이 목표 30%에서 24%로 벌어졌어요");
        assertThat(text(AlertType.R3, "005930", "{\"current\": 0.21, \"target\": 0.14}"))
                .isEqualTo("삼성전자 비중이 목표 14%에서 21%로 벌어졌어요");
        assertThat(text(AlertType.R4, "", "{\"current\": 0.34, \"target\": 0.4}"))
                .isEqualTo("안전자산(채권+현금)이 목표 40%보다 줄어 34%예요");
        assertThat(text(AlertType.R5, "", "{\"drawdown\": -0.0812, \"limit\": -0.1}"))
                .isEqualTo("고점 대비 -8.1% 하락했어요. 말씀하신 한도 -10%에 가까워요");
        assertThat(text(AlertType.R6, "", "{\"year\": 2027}"))
                .isEqualTo("2027년 정기 점검 시기예요. 새 재무가 반영됐어요");
        assertThat(text(AlertType.R7, "000040", "{\"rules\": [\"D1\", \"D3\"]}"))
                .isEqualTo("KR모터스이(가) 상장폐지 위험 기준에 걸렸어요: D1 시가총액 500억 미만, D3 주가 1,000원 미만");
    }

    @Test
    void 모든_종류에_제목과_안내가_있다() {
        for (AlertType t : AlertType.values()) {
            assertThat(texts.title(t)).isNotBlank();
            assertThat(texts.help(t)).isNotBlank();
        }
    }
}
