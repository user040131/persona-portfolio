package kr.team2.portfolio.recommendation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import kr.team2.portfolio.common.BusinessException;
import kr.team2.portfolio.common.ErrorCode;
import kr.team2.portfolio.portfolio.api.PurchaseOrder;
import kr.team2.portfolio.profile.api.Method;
import kr.team2.portfolio.recommendation.api.EditCommand;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 수정 누적(docs/03 §6.2, T-SP-VER-01)·매수 내용 꺼내기(§6.4)·camelCase 변환. */
class EditsAndOrderTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void T_SP_VER_01_뺀_종목은_합집합() {
        Edits v1 = Edits.NONE.merge(new EditCommand(List.of("A"), null, null));
        Edits v2 = v1.merge(new EditCommand(List.of("B", "A"), null, null));
        assertThat(v2.removedCodes()).containsExactly("A", "B");
    }

    @Test
    void 비중_방식은_덮어쓰고_null은_유지하고_마이너스1은_해제() {
        Edits e = Edits.NONE.merge(new EditCommand(null, 0.5, Method.EQUAL));
        assertThat(e).isEqualTo(new Edits(List.of(), 0.5, Method.EQUAL));

        Edits kept = e.merge(new EditCommand(null, null, null));
        assertThat(kept).isEqualTo(e);

        Edits cleared = e.merge(new EditCommand(null, EditCommand.CLEAR, Method.MIN_VARIANCE));
        assertThat(cleared).isEqualTo(new Edits(List.of(), null, Method.MIN_VARIANCE));
    }

    @Test
    void 안전자산_비중은_0에서_1() {
        assertThatThrownBy(() -> Edits.NONE.merge(new EditCommand(null, 1.5, null)))
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void 수정은_JSON으로_저장했다_그대로_읽는다() {
        Edits e = new Edits(List.of("005930"), 0.3, Method.MIN_VARIANCE);
        String s = JSON.writeValueAsString(e);
        assertThat(s).isEqualTo("{\"removedCodes\":[\"005930\"],\"safeRatio\":0.3,\"method\":\"MIN_VARIANCE\"}");
        assertThat(JSON.readValue(s, Edits.class)).isEqualTo(e);
        assertThat(JSON.readValue("{\"removedCodes\":[],\"safeRatio\":null,\"method\":null}", Edits.class))
                .isEqualTo(Edits.NONE);
    }

    @Test
    void 매수_내용은_고른_방식의_주문과_채권_현금_목표비중() throws Exception {
        JsonNode result = golden();
        PurchaseOrder o = RecommendationService.order(result);

        String method = result.path("method_selected").asString();
        assertThat(o.buys()).hasSize(result.path("methods").path(method).path("items").size());
        assertThat(o.buys()).allSatisfy(b -> assertThat(b.shares()).isPositive());
        long spend = o.buys().stream().mapToLong(b -> Math.round(b.shares() * b.price())).sum();
        assertThat(spend).isEqualTo(result.path("allocation").path("stock_amount").asLong());
        assertThat(o.bondUnits()).isEqualTo(104457.704917);
        assertThat(o.cash()).isEqualTo(11_716_650L);
        assertThat(o.asOf()).isEqualTo(LocalDate.of(2026, 8, 19));
        assertThat(o.targetWeights()).containsKeys("005930", "BOND", "CASH");
        assertThat(o.targetWeights().values().stream().mapToDouble(Double::doubleValue).sum())
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.002));
    }

    @Test
    void camelCase는_snake_case_키만_바꾼다() {
        JsonNode in = JSON.readTree("""
                {"as_of": "x", "portfolio_facts": {"n_final": 9},
                 "methods": {"INVERSE_VOL": {"items": [{"code": "005930"}]}},
                 "indicators": {"vol_1y": 0.2, "rsi_14": 0.5, "momentum_6m": 0.1},
                 "combined": {"target_weights": {"005930": 0.1, "BOND": 0.6}}}
                """);
        assertThat(CamelCase.convert(in).toString()).isEqualTo(
                "{\"asOf\":\"x\",\"portfolioFacts\":{\"nFinal\":9},"
                        + "\"methods\":{\"INVERSE_VOL\":{\"items\":[{\"code\":\"005930\"}]}},"
                        + "\"indicators\":{\"vol1y\":0.2,\"rsi14\":0.5,\"momentum6m\":0.1},"
                        + "\"combined\":{\"targetWeights\":{\"005930\":0.1,\"BOND\":0.6}}}");
    }

    private static JsonNode golden() throws Exception {
        try (InputStream in = EditsAndOrderTests.class.getClassLoader().getResourceAsStream("quant-stub/compute.json")) {
            return JSON.readTree(in);
        }
    }
}
