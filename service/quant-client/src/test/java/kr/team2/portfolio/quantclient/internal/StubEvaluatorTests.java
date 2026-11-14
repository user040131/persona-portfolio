package kr.team2.portfolio.quantclient.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.team2.portfolio.common.BusinessException;
import kr.team2.portfolio.common.ErrorCode;
import kr.team2.portfolio.quantclient.api.ComputeRequest;
import kr.team2.portfolio.quantclient.api.EvaluateRequest;
import kr.team2.portfolio.quantclient.api.StockInfo;
import kr.team2.portfolio.quantclient.api.StockList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 엔진 없이 쓰는 I8 대역이 엔진과 같은 규칙으로 평가하는지 (app/domain/alerts.py). */
class StubEvaluatorTests {

    private static final StockList STOCKS = new StockList(LocalDate.of(2026, 8, 19), 1, List.of(
            new StockInfo("A", "에이", "전기·전자", "전기·전자", false, 1000.0, List.of()),
            new StockInfo("B", "비", "화학", "화학", false, 100.0, List.of("D3"))));

    private static JsonNode eval(List<ComputeRequest.Holding> h, double bondUnits, long cash, Map<String, Double> targets) {
        var settings = RestQuantClientTests.goldenRequest().settings();
        String out = new StubEvaluator(STOCKS).evaluate(new EvaluateRequest(settings, h, bondUnits, cash, targets,
                null, null, null));
        return JsonMapper.builder().build().readTree(out);
    }

    @Test
    void 평가금액과_비중_알림_조건() {
        // A 10주 = 10,000 / B 30주 = 3,000 / 채권 0 / 현금 7,000 → 합계 20,000
        JsonNode r = eval(List.of(new ComputeRequest.Holding("A", 10), new ComputeRequest.Holding("B", 30)), 0, 7_000,
                Map.of("A", 0.3, "B", 0.15, "BOND", 0.2, "CASH", 0.35));
        assertThat(r.path("total_value").asLong()).isEqualTo(20_000);
        assertThat(r.path("by_asset").path("stock").asDouble()).isEqualTo(0.65);
        assertThat(r.path("assets").get(0).path("asset").asString()).isEqualTo("A");
        assertThat(r.path("assets").get(0).path("weight_in_stock").asDouble()).isEqualTo(0.7692);

        List<String> types = r.path("conditions").valueStream()
                .map(c -> c.path("type").asString() + ":" + c.path("target").asString()).toList();
        // 주식 안 A 77%·B 23% > 20% (R1), 전기·전자 77% > 40% (R2), A 50% vs 목표 30% (R3), 채권 0 vs 20% (R3),
        // 안전자산 35% < 55% − 5%p (R4), B 상장폐지 위험 (R7)
        assertThat(types).containsExactly("R1:A", "R1:B", "R2:전기·전자", "R3:A", "R3:BOND", "R4:", "R7:B");
    }

    @Test
    void 없는_종목이면_UNKNOWN_STOCK() {
        assertThatThrownBy(() -> eval(List.of(new ComputeRequest.Holding("Z", 1)), 0, 0, Map.of()))
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.UNKNOWN_STOCK);
    }
}
