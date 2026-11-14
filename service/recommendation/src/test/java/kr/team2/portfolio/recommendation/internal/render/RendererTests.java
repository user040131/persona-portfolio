package kr.team2.portfolio.recommendation.internal.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import kr.team2.portfolio.common.Texts;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 문구 템플릿 (docs/03 §6.3, §8.7 T-SP-REN-*). */
class RendererTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Renderer renderer = new Renderer(Texts.load("messages_reasons.properties"));

    private static final Renderer.ProfileContext GOLDEN = new Renderer.ProfileContext(
            "위험중립형", "10% 이하", false, false, "15개 안팎", false, false);

    private static JsonNode json(String s) {
        return JSON.readTree(s);
    }

    @Test
    void 숫자_형식() {
        assertThat(Texts.format(0.15, "%0")).isEqualTo("15%");
        assertThat(Texts.format(-0.10, "%0")).isEqualTo("-10%");
        assertThat(Texts.format(0.145, "%0")).isEqualTo("15%");          // 0.145 → 14.5 → 반올림 15
        assertThat(Texts.format(-0.0987, "%0.0")).isEqualTo("-9.9%");
        assertThat(Texts.format(0.103406, "+%0.0")).isEqualTo("+10.3%");
        assertThat(Texts.format(-0.05, "+%0.0")).isEqualTo("-5.0%");
        assertThat(Texts.format(-0.0001, "%0")).isEqualTo("0%");          // -0% 아님
        assertThat(Texts.format(6.88425, "0.0")).isEqualTo("6.9");
        assertThat(Texts.format(0.808558, "0.00")).isEqualTo("0.81");
        assertThat(Texts.format(78725L, "#,###")).isEqualTo("78,725");
        assertThat(Texts.format(7.0, null)).isEqualTo("7");
    }

    @Test
    void T_SP_REN_01_종목_선택_근거() {
        JsonNode stock = json("""
                {"facts": {"selection": {"top_factors": ["VALUE", "QUALITY"], "group": "운송장비·부품",
                                         "group_n": 41, "rank_in_group": 2},
                           "level": {"per": 4.12, "pbr": 0.71, "roe": 0.173},
                           "profile_link": {"factor": "STABILITY", "pct_top": 0.08, "value": 0.35},
                           "cautions": [{"code": "HIGH_VOL", "vol_1y": 0.52}, {"code": "WEAK_FACTOR", "factor": "MOMENTUM"}],
                           "role": {"code": "DIVERSIFY", "sector": "운송장비·부품"}}}
                """);
        List<Renderer.Reason> r = renderer.stockReasons(stock, "위험중립형");

        assertThat(r).extracting(Renderer.Reason::text).containsExactly(
                "운송장비·부품 41개 종목 중 가치·수익성 점수가 높아요 (업종 안 2위)",
                "이익 대비 주가(PER) 4.1배 · 자산 대비 주가(PBR) 0.71배 · 자기자본이익률(ROE) 17.3%",
                "위험중립형이라 빚이 적은 회사를 중요하게 봤어요. 부채비율이 업종 상위 8%예요",
                "최근 1년 변동성이 52%로 큰 편이에요",
                "추세 점수는 업종 하위 30%예요",
                "갖고 계신 종목에 없는 업종(운송장비·부품)이라 분산에 도움이 돼요");
        assertThat(r).extracting(Renderer.Reason::type)
                .containsExactly("SELECTION", "LEVEL", "PROFILE_LINK", "CAUTION", "CAUTION", "ROLE");
    }

    @Test
    void 전체_후보_그룹과_빈_지표() {
        JsonNode stock = json("""
                {"facts": {"selection": {"top_factors": ["DIVIDEND"], "group": "__ALL__", "group_n": 9, "rank_in_group": 1},
                           "level": {"per": null, "pbr": 1.2, "roe": null},
                           "profile_link": null, "cautions": [], "role": {"code": "NO_HOLDINGS", "sector": "x"}}}
                """);
        assertThat(renderer.stockReasons(stock, "공격추구형")).extracting(Renderer.Reason::text).containsExactly(
                "전체 후보 9개 종목 중 배당 점수가 높아요 (업종 안 1위)",
                "자산 대비 주가(PBR) 1.20배",
                "보유 종목이 없어 분산 효과는 따로 보지 않았어요");
    }

    @Test
    void T_SP_REN_02_손실_한도_때문에_주식을_줄임() {
        JsonNode result = json("""
                {"allocation": {"stock": 0.47, "bond": 0.38, "cash": 0.15, "expected_mdd": -0.0987, "cash_from_rounding": 0,
                                "lookback": {"start": "2010-01-04", "end": "2026-08-19"}},
                 "method_selected": "INVERSE_VOL",
                 "portfolio_facts": {"stock_binding": "LOSS_LIMIT", "lookback_years": 7.0, "stock_cap": 0.55,
                                     "min_cash": 0.15, "loss_limit": -0.10, "n_final": 12, "n_reduced": false, "skipped": {}},
                 "warnings": []}
                """);
        assertThat(renderer.allocationReasons(result, GOLDEN)).contains(
                "말씀하신 손실 한도(-10%)를 넘지 않도록, 예측이 빗나갈 때를 대비해 여유를 두고 주식을 47%로 정했어요. "
                        + "2010년 이후 시장을 그대로 겪었다면 최대 하락폭은 -9.9%예요");
    }

    @Test
    void 기준_추천_결과의_포트폴리오_근거_전체() throws Exception {
        JsonNode result = golden();
        assertThat(renderer.allocationReasons(result, GOLDEN)).containsExactly(
                "현금 39%: 1년 안에 쓸 돈이 '10% 이하'라고 하셔서 바로 꺼낼 수 있게 남겼어요. 비상금이 없다고 하셔서 5%p를 더 뒀어요. "
                        + "채권도 금리가 오르면 값이 떨어지므로, 손실 한도를 지키려고 24%p를 더 현금으로 뒀어요",
                "채권 53%: 흔들림을 줄이려고 담았어요",
                "말씀하신 손실 한도(-10%)를 넘지 않도록, 예측이 빗나갈 때를 대비해 여유를 두고 주식을 8%로 정했어요. "
                        + "2010년 이후 시장을 그대로 겪었다면 최대 하락폭은 -7.0%예요",
                "'15개 안팎' 관리할 수 있다고 하셔서 4종목으로 나눴어요. 주식 몫이 작아 종목당 50만 원 이상이 되도록 줄였어요",
                "위험중립형이라 '변동성 역수가중' 방식을 기본으로 보여드려요",
                "빠진 종목: 변동성이 큼 43개, 단기 과열 139개",
                "주식 수를 맞추고 남은 16,650원은 현금에 더했어요");
    }

    @Test
    void 기준_추천_결과의_모든_종목에_근거가_있다() throws Exception {
        for (JsonNode s : golden().path("stocks")) {
            List<Renderer.Reason> r = renderer.stockReasons(s, "위험중립형");
            assertThat(r).extracting(Renderer.Reason::type).contains("SELECTION", "LEVEL", "PROFILE_LINK", "ROLE");
            assertThat(r).allSatisfy(x -> assertThat(x.text()).doesNotContain("{", "null"));
        }
    }

    @Test
    void 경고_문구() throws Exception {
        List<String> texts = golden().path("warnings").valueStream().map(renderer::warning).toList();
        assertThat(texts).containsExactlyInAnyOrder(
                "주식 몫이 작아 15종목 대신 4종목으로 줄였어요",
                "갖고 계신 종목까지 합치면 과거 최대 하락폭이 -11.3%로, 말씀하신 손실 한도(-10%)를 넘어요");
    }

    @Test
    void 주식이_없고_직접_정한_비중이면() {
        JsonNode result = json("""
                {"allocation": {"stock": 0.0, "bond": 0.45, "cash": 0.55, "expected_mdd": -0.02, "cash_from_rounding": 0,
                                "lookback": {"start": "2010-01-04", "end": "2026-08-19"}},
                 "method_selected": "INVERSE_VOL",
                 "portfolio_facts": {"stock_binding": "LOSS_LIMIT", "lookback_years": 7.0, "stock_cap": 0.55,
                                     "min_cash": 0.15, "loss_limit": -0.03, "n_final": 0, "n_reduced": false, "skipped": {}},
                 "warnings": [{"type": "NO_STOCK_ALLOCATION", "detail": {}}]}
                """);
        assertThat(renderer.allocationReasons(result, GOLDEN)).containsExactly(
                "현금 55%: 1년 안에 쓸 돈이 '10% 이하'라고 하셔서 바로 꺼낼 수 있게 남겼어요. 비상금이 없다고 하셔서 5%p를 더 뒀어요. "
                        + "채권도 금리가 오르면 값이 떨어지므로, 손실 한도를 지키려고 40%p를 더 현금으로 뒀어요",
                "채권 45%: 흔들림을 줄이려고 담았어요",
                "말씀하신 손실 한도(-3%)에 여유를 두면 주식을 담기 어려워 채권과 현금으로만 나눴어요. "
                        + "2010년 이후 시장을 그대로 겪었다면 최대 하락폭은 -2.0%예요");
    }

    @Test
    void 쓸_돈_없음_상관없음_직접_고른_방식() {
        JsonNode result = json("""
                {"allocation": {"stock": 0.9, "bond": 0.1, "cash": 0.0, "expected_mdd": -0.3, "cash_from_rounding": 0},
                 "method_selected": "EQUAL",
                 "portfolio_facts": {"stock_binding": "CAP", "lookback_years": 7.0, "stock_cap": 0.9,
                                     "min_cash": 0.0, "loss_limit": null, "n_final": 20, "n_reduced": false, "skipped": {}},
                 "warnings": []}
                """);
        var p = new Renderer.ProfileContext("공격추구형", "없음", true, true, "상관없음", true, true);
        assertThat(renderer.allocationReasons(result, p)).containsExactly(
                "현금 0%: 1년 안에 쓸 돈이 없다고 하셔서 따로 떼어 두지 않았어요",
                "채권 10%: 흔들림을 줄이려고 담았어요",
                "공격추구형의 주식 상한 90%에 맞췄어요",
                "종목 수는 상관없다고 하셔서 20종목으로 나눴어요",
                "직접 고르신 '동일가중' 방식으로 보여드려요");
    }

    @Test
    void 왜_없나요_문구() throws Exception {
        JsonNode screening = golden().path("screening");
        JsonNode samsung = screening.path("005930");
        assertThat(renderer.whyNot(samsung.path("reason").asString(), samsung.path("params"), "위험중립형"))
                .matches("전기·전자 \\d+개 중 \\d+위라 순위 밖이에요");
        assertThat(renderer.whyNot("DELIST_RISK", json("{\"rules\": [\"D1\", \"D3\"]}"), "위험중립형"))
                .isEqualTo("상장폐지 위험 기준에 걸려요: D1 시가총액 500억 미만, D3 주가 1,000원 미만");
        assertThat(renderer.whyNot("HIGH_VOLATILITY", json("{\"pct\": 0.04}"), "위험중립형"))
                .isEqualTo("변동성이 커서 위험중립형 기준에서 뺐어요 (상위 4%)");
        assertThat(renderer.whyNot("PRICE_TOO_HIGH", json("{\"close\": 1250000.0}"), "x"))
                .isEqualTo("1주 가격(1,250,000원)이 배정 금액보다 커요");
        assertThat(renderer.whyNot(null, json("{}"), "x")).isEqualTo("이번 추천에 들어 있어요");
        // 엔진이 내는 모든 사유에 문구가 있다
        screening.valueStream().map(e -> e.path("reason")).filter(JsonNode::isString).forEach(r ->
                assertThat(renderer.whyNot(r.asString(), json("{}"), "x")).isNotEqualTo(r.asString()));
    }

    @Test
    void 라벨() {
        assertThat(renderer.labels("method", List.of("EQUAL", "MIN_VARIANCE")))
                .isEqualTo(Map.of("EQUAL", "동일가중", "MIN_VARIANCE", "최소분산"));
    }

    static JsonNode golden() throws Exception {
        try (InputStream in = RendererTests.class.getClassLoader().getResourceAsStream("quant-stub/compute.json")) {
            return JSON.readTree(in);
        }
    }
}
