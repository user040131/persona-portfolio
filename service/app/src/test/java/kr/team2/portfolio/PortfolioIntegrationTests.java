package kr.team2.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import kr.team2.portfolio.auth.api.AuthFacade;
import kr.team2.portfolio.auth.api.AuthenticatedUser;
import kr.team2.portfolio.auth.api.CurrentUser;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * 매수 완료 후 포트폴리오 조회·종목 상세·직접 수정 (01 FR-PF-02~04, 03 §7.3 #16~18, T-INT-09).
 * 엔진 대신 대역(대역 종목 종가 + 기준 추천 결과)을 쓴다.
 */
@SpringBootTest(properties = "quant.stub-enabled=true")
@AutoConfigureMockMvc
class PortfolioIntegrationTests extends MySqlIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuthFacade auth;

    @Autowired
    JsonMapper json;

    AuthenticatedUser me;

    @BeforeEach
    void 온보딩한_사용자() throws Exception {
        CurrentUser u = auth.register("pf" + System.nanoTime() % 1_000_000_000L, "password123");
        me = new AuthenticatedUser(u.id(), u.username(), "unused");
        mvc.perform(post("/api/onboarding").with(user(me)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(OnboardingIntegrationTests.goldenRequest())))
                .andExpect(status().isCreated());
    }

    private void 추천을_매수한다() throws Exception {
        String body = mvc.perform(post("/api/recommendations").with(user(me)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(body).path("recommendationId").asLong();
        mvc.perform(post("/api/recommendations/" + id + "/purchase").with(user(me)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk());
    }

    @Test
    void 매수_전에는_포트폴리오_화면_대신_추천이나_프로필로_보낸다() throws Exception {
        mvc.perform(get("/portfolio").with(user(me))).andExpect(redirectedUrl("/"));
        mvc.perform(get("/").with(user(me))).andExpect(redirectedUrl("/profile"));
    }

    @Test
    void 매수_후_평가는_종목_채권_현금과_목표_비중을_준다() throws Exception {
        추천을_매수한다();
        mvc.perform(get("/").with(user(me))).andExpect(redirectedUrl("/portfolio"));
        mvc.perform(get("/portfolio").with(user(me))).andExpect(status().isOk());

        mvc.perform(get("/api/portfolio").with(user(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2026-08-19"))
                .andExpect(jsonPath("$.purchased").value(true))
                .andExpect(jsonPath("$.purchasedAt").value("2026-08-19"))
                // 3,000만 원 + 삼성전자 20주(4,950,000원)
                .andExpect(jsonPath("$.totalValue").value(34_950_000))
                .andExpect(jsonPath("$.stocks.length()").value(5))
                .andExpect(jsonPath("$.stocks[0].code").value("005930"))
                .andExpect(jsonPath("$.stocks[0].name").value("삼성전자"))
                .andExpect(jsonPath("$.stocks[0].origin").value("EXISTING"))
                .andExpect(jsonPath("$.stocks[0].avgPrice").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.stocks[0].targetWeight").value(0.1416))
                .andExpect(jsonPath("$.stocks[1].origin").value("SERVICE"))
                .andExpect(jsonPath("$.stocks[1].returnRate").value(0.0))
                .andExpect(jsonPath("$.bond.value").value(15_900_000))
                .andExpect(jsonPath("$.cash.value").value(11_716_650))
                .andExpect(jsonPath("$.targetByAsset.bond").value(0.4549))
                .andExpect(jsonPath("$.limits.sectorLimit").value(0.40))
                // 삼성전자가 주식의 3분의 2 → 종목·업종 한도 조건
                .andExpect(jsonPath("$.conditions[?(@.type == 'R1')].target").value(Matchers.hasItem("005930")));

        mvc.perform(get("/api/portfolio/stocks/005930").with(user(me)))
                .andExpect(jsonPath("$.quantity").value(20))
                .andExpect(jsonPath("$.value").value(4_950_000));
        mvc.perform(get("/api/portfolio/stocks/000660").with(user(me)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 직접_고치면_수량_평균가_채권_현금이_바뀌고_기준일이_옮겨진다() throws Exception {
        추천을_매수한다();
        String picked = jdbc.queryForObject("SELECT h.code FROM holding h JOIN portfolio p ON p.id = h.portfolio_id "
                + "WHERE p.user_id = ? AND h.origin = 'SERVICE' ORDER BY h.code LIMIT 1", String.class, me.id());

        update(Map.of(
                "holdings", List.of(
                        Map.of("code", "005930", "quantity", 25, "avgPrice", 230_000),   // 수량·평균가
                        Map.of("code", picked, "quantity", 0),                          // 삭제
                        Map.of("code", "000660", "quantity", 3)),                        // 추가 (SK하이닉스)
                "bondAmount", 10_000_000,
                "cash", 5_000_000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stocks.length()").value(5))
                .andExpect(jsonPath("$.cash.value").value(5_000_000))
                .andExpect(jsonPath("$.bond.value").value(10_000_000))
                .andExpect(jsonPath("$.stocks[?(@.code == '005930')].returnRate").value(Matchers.hasItem(0.0761)))
                .andExpect(jsonPath("$.targetOnly[0].code").value(picked));

        Map<String, Object> samsung = jdbc.queryForMap("SELECT h.quantity, h.avg_price FROM holding h "
                + "JOIN portfolio p ON p.id = h.portfolio_id WHERE p.user_id = ? AND h.code = '005930'", me.id());
        assertThat(samsung).containsEntry("quantity", 25).containsEntry("avg_price", 230_000.0);
        assertThat(jdbc.queryForObject("SELECT h.origin FROM holding h JOIN portfolio p ON p.id = h.portfolio_id "
                + "WHERE p.user_id = ? AND h.code = '000660'", String.class, me.id())).isEqualTo("EXISTING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM holding h JOIN portfolio p ON p.id = h.portfolio_id "
                + "WHERE p.user_id = ? AND h.code = ?", Integer.class, me.id(), picked)).isZero();

        // 평균가 0을 보내면 '모름'으로 되돌린다. 보내지 않은 값은 그대로
        update(Map.of("holdings", List.of(Map.of("code", "005930", "quantity", 25, "avgPrice", 0))))
                .andExpect(jsonPath("$.stocks[?(@.code == '005930')].avgPrice").value(Matchers.hasItem(Matchers.nullValue())))
                .andExpect(jsonPath("$.cash.value").value(5_000_000));
    }

    @Test
    void 잘못된_수정은_막는다() throws Exception {
        추천을_매수한다();
        update(Map.of("holdings", List.of(Map.of("code", "999999", "quantity", 1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("UNKNOWN_STOCK"));
        update(Map.of("holdings", List.of(Map.of("code", "005930", "quantity", -1))))
                .andExpect(status().isUnprocessableEntity());
        update(Map.of("cash", -5)).andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT h.quantity FROM holding h JOIN portfolio p ON p.id = h.portfolio_id "
                + "WHERE p.user_id = ? AND h.code = '005930'", Integer.class, me.id())).isEqualTo(20);
    }

    private ResultActions update(Map<String, Object> body) throws Exception {
        return mvc.perform(patch("/api/portfolio").with(user(me)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
}
