package kr.team2.portfolio.quantclient.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import kr.team2.portfolio.common.BusinessException;
import kr.team2.portfolio.common.ErrorCode;
import java.util.List;
import java.util.Map;
import kr.team2.portfolio.quantclient.api.ComputeRequest;
import kr.team2.portfolio.quantclient.api.EngineHealth;
import kr.team2.portfolio.quantclient.api.EvaluateRequest;
import org.springframework.http.HttpMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 엔진 호출 규칙 (docs/02 §5): 서비스 키 헤더, 응답 변환, 오류 코드 전달. */
class RestQuantClientTests {

    private MockRestServiceServer server;
    private RestQuantClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://engine")
                .defaultHeader(QuantClientConfig.SERVICE_KEY_HEADER, "secret");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestQuantClient(builder.build());
    }

    @Test
    void 서비스_키를_보내고_snake_case_응답을_바꾼다() {
        server.expect(requestTo("http://engine/internal/v1/health"))
                .andExpect(header("X-Service-Key", "secret"))
                .andRespond(withSuccess("{\"status\":\"ok\",\"data_loaded\":false}", MediaType.APPLICATION_JSON));

        assertThat(client.health()).isEqualTo(new EngineHealth("ok", false));
        server.verify();
    }

    @Test
    void 엔진의_DATA_NOT_LOADED는_그대로_전달한다() {
        server.expect(requestTo("http://engine/internal/v1/stocks"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"DATA_NOT_LOADED\",\"message\":\"x\",\"detail\":{}}}"));

        assertThatThrownBy(client::stocks)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.DATA_NOT_LOADED);
    }

    /** docs/03 §3 I6 요청 예시와 같은 값 */
    static ComputeRequest goldenRequest() {
        return new ComputeRequest(
                new ComputeRequest.Settings("NEUTRAL", -0.10, 0.15, 0.55, 15,
                        Map.of("VALUE", 0.2727, "QUALITY", 0.2727, "MOMENTUM", 0.2273, "STABILITY", 0.1364,
                                "DIVIDEND", 0.0909),
                        0.10, true, 0.20, 0.40, "INVERSE_VOL", List.of()),
                30_000_000L,
                new ComputeRequest.Existing(List.of(new ComputeRequest.Holding("005930", 20)), 0, 0),
                new ComputeRequest.Edits(List.of(), null, null));
    }

    @Test
    void 추천_계산은_snake_case로_보내고_응답_본문을_그대로_준다() {
        server.expect(requestTo("http://engine/internal/v1/recommendations/compute"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Service-Key", "secret"))
                .andExpect(content().json("""
                        {"settings": {"grade": "NEUTRAL", "loss_limit": -0.10, "min_cash": 0.15, "stock_cap": 0.55,
                                      "n_stocks": 15, "vol_exclude_top_pct": 0.10, "exclude_overheat": true,
                                      "stock_limit": 0.20, "sector_limit": 0.40, "default_method": "INVERSE_VOL",
                                      "excluded_sectors": [],
                                      "factor_weights": {"VALUE": 0.2727, "QUALITY": 0.2727, "MOMENTUM": 0.2273,
                                                         "STABILITY": 0.1364, "DIVIDEND": 0.0909}},
                         "invest_amount": 30000000,
                         "existing": {"holdings": [{"code": "005930", "quantity": 20}], "bond_units": 0, "cash": 0},
                         "edits": {"removed_codes": [], "safe_ratio": null, "method": null}}
                        """, org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"as_of\":\"2026-08-19\"}", MediaType.APPLICATION_JSON));

        assertThat(client.computeRecommendation(goldenRequest())).isEqualTo("{\"as_of\":\"2026-08-19\"}");
        server.verify();
    }

    static EvaluateRequest evaluateRequest() {
        return new EvaluateRequest(goldenRequest().settings(), List.of(new ComputeRequest.Holding("005930", 20)),
                102345.6, 4_320_000L, Map.of("005930", 0.13, "BOND", 0.30, "CASH", 0.12),
                java.time.LocalDate.of(2026, 8, 19), java.time.LocalDate.of(2026, 8, 19), null);
    }

    @Test
    void 포트폴리오_평가는_날짜를_ISO_문자열로_보낸다() {
        server.expect(requestTo("http://engine/internal/v1/portfolio/evaluate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"holdings": [{"code": "005930", "quantity": 20}], "bond_units": 102345.6, "cash": 4320000,
                         "targets": {"005930": 0.13, "BOND": 0.30, "CASH": 0.12},
                         "purchased_at": "2026-08-19", "holdings_changed_at": "2026-08-19", "last_r6_year": null}
                        """))
                .andRespond(withSuccess("{\"total_value\":1}", MediaType.APPLICATION_JSON));

        assertThat(client.evaluatePortfolio(evaluateRequest())).isEqualTo("{\"total_value\":1}");
        server.verify();
    }

    @Test
    void 조건에_맞는_종목이_없다는_엔진_오류는_그대로_전달한다() {
        server.expect(requestTo("http://engine/internal/v1/recommendations/compute"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"INSUFFICIENT_UNIVERSE\",\"message\":\"x\",\"detail\":{}}}"));

        assertThatThrownBy(() -> client.computeRecommendation(goldenRequest()))
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.INSUFFICIENT_UNIVERSE);
    }

    @Test
    void 서비스_키가_틀려_401이면_QUANT_UNAVAILABLE() {
        server.expect(requestTo("http://engine/internal/v1/health"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"x\",\"detail\":{}}}"));

        assertThatThrownBy(client::health)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.QUANT_UNAVAILABLE);
    }

    @Test
    void 연결이_안_되면_QUANT_UNAVAILABLE() {
        server.expect(requestTo("http://engine/internal/v1/health"))
                .andRespond(request -> {
                    throw new IOException("connection refused");
                });

        assertThatThrownBy(client::health)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.QUANT_UNAVAILABLE);
    }
}
