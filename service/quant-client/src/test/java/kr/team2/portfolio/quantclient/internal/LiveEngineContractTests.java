package kr.team2.portfolio.quantclient.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import kr.team2.portfolio.common.BusinessException;
import kr.team2.portfolio.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

/**
 * 실제로 떠 있는 Python 엔진과의 계약 확인. 평소에는 건너뛰고, 엔진을 띄운 뒤 아래처럼 실행한다.
 * <pre>QUANT_LIVE_URL=http://127.0.0.1:8000 QUANT_SERVICE_KEY=dev-only-change-me ./gradlew :quant-client:test</pre>
 */
@EnabledIfEnvironmentVariable(named = "QUANT_LIVE_URL", matches = "http.+")
class LiveEngineContractTests {

    /** 운영과 같은 방식(QuantClientConfig.build)으로 만든다. HTTP 버전 같은 설정 차이를 여기서 잡으려고. */
    private static RestQuantClient client(String key) {
        return new RestQuantClient(QuantClientConfig.build(RestClient.builder(), System.getenv("QUANT_LIVE_URL"), key,
                Duration.ofSeconds(2), Duration.ofSeconds(15)));
    }

    @Test
    void 엔진_health를_읽는다() {
        String key = System.getenv().getOrDefault("QUANT_SERVICE_KEY", "dev-only-change-me");
        assertThat(client(key).health().status()).isEqualTo("ok");
    }

    @Test
    void 종목_목록_I4를_읽는다() {
        String key = System.getenv().getOrDefault("QUANT_SERVICE_KEY", "dev-only-change-me");
        var list = client(key).stocks();
        assertThat(list.items()).hasSizeGreaterThan(800);
        var samsung = list.items().stream().filter(s -> s.code().equals("005930")).findFirst().orElseThrow();
        assertThat(samsung.name()).isEqualTo("삼성전자");
        assertThat(samsung.lastClose()).isPositive();
        assertThat(samsung.delistRules()).isEmpty();
        assertThat(list.items().stream().filter(s -> !s.delistRules().isEmpty()).count()).isPositive();
    }

    @Test
    void 추천_계산_I6_기준_프로필() {
        String key = System.getenv().getOrDefault("QUANT_SERVICE_KEY", "dev-only-change-me");
        var body = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(client(key).computeRecommendation(RestQuantClientTests.goldenRequest()));
        assertThat(body.path("allocation").path("stock").asDouble()).isEqualTo(0.08);
        assertThat(body.path("portfolio_facts").path("stock_binding").asString()).isEqualTo("LOSS_LIMIT");
        assertThat(body.path("stocks").size()).isEqualTo(4);
        assertThat(body.path("screening").path("005930").path("reason").asString()).isEqualTo("RANK_OUT");
    }

    @Test
    void 포트폴리오_평가_I8() {
        String key = System.getenv().getOrDefault("QUANT_SERVICE_KEY", "dev-only-change-me");
        var body = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(client(key).evaluatePortfolio(RestQuantClientTests.evaluateRequest()));
        assertThat(body.path("as_of").asString()).isEqualTo("2026-08-19");
        assertThat(body.path("assets").get(0).path("asset").asString()).isEqualTo("005930");
        assertThat(body.path("assets").get(0).path("value").asLong()).isEqualTo(20 * 247_500L);
        assertThat(body.path("bond_index").asDouble()).isPositive();
        assertThat(body.path("conditions").isArray()).isTrue();
    }

    @Test
    void 리밸런싱_수량_I9() {
        String key = System.getenv().getOrDefault("QUANT_SERVICE_KEY", "dev-only-change-me");
        var req = kr.team2.portfolio.quantclient.api.RebalanceRequest.of(RestQuantClientTests.evaluateRequest(), "R3", "BOND");
        var body = tools.jackson.databind.json.JsonMapper.builder().build().readTree(client(key).rebalancePlan(req));
        assertThat(body.path("alert_type").asString()).isEqualTo("R3");
        assertThat(body.path("trades").isArray()).isTrue();
        assertThat(body.path("before").path("by_asset").path("stock").isNumber()).isTrue();
        assertThat(body.path("after").path("quantities").path("005930").asInt()).isPositive();
    }

    @Test
    void 과거_성과_카드_I7과_백테스트_작업_I10_I11() throws Exception {
        String key = System.getenv().getOrDefault("QUANT_SERVICE_KEY", "dev-only-change-me");
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        RestQuantClient c = client(key);
        var card = mapper.readTree(c.historyCard(RestQuantClientTests.goldenRequest().settings(), 2015));
        assertThat(card.path("start").asString()).isEqualTo("2015-04-01");
        assertThat(card.path("max_drawdown").asDouble()).isNegative();

        String job = c.createBacktest(new kr.team2.portfolio.quantclient.api.BacktestRequest(2025, null, 0.003,
                java.util.List.of("C"), java.util.Map.of("VALUE", 0.3, "QUALITY", 0.3, "MOMENTUM", 0.25,
                "STABILITY", 0.15, "DIVIDEND", 0.0), java.util.List.of()));
        String status = "";
        for (int i = 0; i < 60 && !status.equals("DONE"); i++) {
            Thread.sleep(500);
            status = mapper.readTree(c.backtestStatus(job)).path("status").asString();
        }
        assertThat(status).isEqualTo("DONE");
        assertThat(mapper.readTree(c.backtestNav(job, "KOSPI", "M")).path("strategies").has("KOSPI")).isTrue();
        assertThat(c.backtestCsv(job)).startsWith("date,");
        assertThatThrownBy(() -> c.backtestStatus("00000000-0000-0000-0000-000000000000"))
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.BACKTEST_NOT_FOUND);
    }

    @Test
    void 서비스_키가_틀리면_QUANT_UNAVAILABLE() {
        assertThatThrownBy(() -> client("wrong-key").health())
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.QUANT_UNAVAILABLE);
    }
}
