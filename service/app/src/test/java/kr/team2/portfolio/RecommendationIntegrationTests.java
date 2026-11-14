package kr.team2.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.team2.portfolio.auth.api.AuthFacade;
import kr.team2.portfolio.auth.api.AuthenticatedUser;
import kr.team2.portfolio.auth.api.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 추천 생성 → 수정(버전) → 왜 없나요 → 매수 완료 (01 FR-REC·FR-PF-01, 03 §8.9 T-INT-05·07·08·09·10).
 * 엔진 대신 대역 응답(실제 엔진의 기준 프로필 결과, quant-stub/compute.json)을 쓴다.
 * 대역은 입력과 관계없이 같은 결과를 주므로, 여기서는 Spring 쪽 저장·버전·문구·매수 규칙만 본다.
 */
@SpringBootTest(properties = "quant.stub-enabled=true")
@AutoConfigureMockMvc
class RecommendationIntegrationTests extends MySqlIntegrationTest {

    /** 기준 결과에 들어 있는 종목 (INVERSE_VOL 1위) */
    private static final String PICKED = "013520";

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
        me = newUser();
        mvc.perform(post("/api/onboarding").with(user(me)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(OnboardingIntegrationTests.goldenRequest())))
                .andExpect(status().isCreated());
    }

    private AuthenticatedUser newUser() {
        CurrentUser u = auth.register("rec" + System.nanoTime() % 1_000_000_000L, "password123");
        return new AuthenticatedUser(u.id(), u.username(), "unused");
    }

    @Test
    void 추천을_만들면_결과와_한국어_근거를_준다() throws Exception {
        create(Map.of())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.latestVersion").value(1))
                .andExpect(jsonPath("$.investAmount").value(30_000_000))
                .andExpect(jsonPath("$.canPurchase").value(true))
                .andExpect(jsonPath("$.asOf").value("2026-08-19"))
                .andExpect(jsonPath("$.allocation.stock").value(0.08))
                .andExpect(jsonPath("$.allocation.cashFromRounding").value(16_650))
                .andExpect(jsonPath("$.portfolioFacts.stockBinding").value("LOSS_LIMIT"))
                .andExpect(jsonPath("$.methods.INVERSE_VOL.items.length()").value(4))
                .andExpect(jsonPath("$.stocks[0].indicators.vol1y").exists())
                .andExpect(jsonPath("$.stocks[0].reasons[0].type").value("SELECTION"))
                .andExpect(jsonPath("$.allocationReasons[0]").value(startsWith("현금 39%: 1년 안에 쓸 돈이 '10% 이하'")))
                .andExpect(jsonPath("$.warnings[*].text").value(hasItem(startsWith("갖고 계신 종목까지 합치면"))))
                .andExpect(jsonPath("$.names['005930']").value("삼성전자"))
                .andExpect(jsonPath("$.profile.gradeLabel").value("위험중립형"))
                .andExpect(jsonPath("$.labels.methods.INVERSE_VOL").value("변동성 역수가중"))
                .andExpect(jsonPath("$.edits.removedCodes.length()").value(0))
                .andExpect(jsonPath("$.screening").doesNotExist());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT invest_amount, latest_version, purchased_version, as_of FROM recommendation WHERE user_id = ?", me.id());
        assertThat(row).containsEntry("invest_amount", 30_000_000L).containsEntry("latest_version", 1);
        assertThat(row.get("purchased_version")).isNull();
        // 저장된 결과에는 왜 없나요용 screening까지 그대로 있다
        assertThat(jdbc.queryForObject("SELECT JSON_LENGTH(result, '$.screening') FROM recommendation_version v "
                + "JOIN recommendation r ON r.id = v.recommendation_id WHERE r.user_id = ?", Integer.class, me.id()))
                .isGreaterThan(800);

        long newer = idOf(create(Map.of()));
        mvc.perform(get("/").with(user(me))).andExpect(redirectedUrl("/recommendations/" + newer));
    }

    @Test
    void T_INT_10_다른_사용자의_추천은_404() throws Exception {
        long id = idOf(create(Map.of()));
        AuthenticatedUser other = newUser();
        mvc.perform(get("/api/recommendations/" + id).with(user(other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RECOMMENDATION_NOT_FOUND"));
        mvc.perform(post("/api/recommendations/" + id + "/purchase").with(user(other)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 수정할_때마다_버전이_늘고_뺀_종목은_누적된다() throws Exception {
        long id = idOf(create(Map.of()));
        edit(id, Map.of("removedCodes", List.of("453340")))   // 대역은 입력과 관계없이 같은 결과
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));
        edit(id, Map.of("removedCodes", List.of(PICKED), "method", "EQUAL", "safeRatio", 0.5))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.edits.removedCodes[0]").value("453340"))
                .andExpect(jsonPath("$.edits.removedCodes[1]").value(PICKED))
                .andExpect(jsonPath("$.edits.method").value("EQUAL"))
                .andExpect(jsonPath("$.edits.safeRatio").value(0.5));
        edit(id, Map.of("safeRatio", -1))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.edits.safeRatio").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.edits.method").value("EQUAL"));

        // 지난 버전도 볼 수 있다
        mvc.perform(get("/api/recommendations/" + id).param("version", "2").with(user(me)))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.latestVersion").value(4))
                .andExpect(jsonPath("$.canPurchase").value(false))
                .andExpect(jsonPath("$.edits.removedCodes.length()").value(1));
        mvc.perform(get("/api/recommendations/" + id).param("version", "9").with(user(me)))
                .andExpect(status().isNotFound());

        edit(id, Map.of("removedCodes", List.of("999999")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("UNKNOWN_STOCK"));
        edit(id, Map.of("safeRatio", 1.5)).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void T_INT_07_왜_없나요() throws Exception {
        long id = idOf(create(Map.of()));
        mvc.perform(get("/api/recommendations/" + id + "/why-not/005930").with(user(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("삼성전자"))
                .andExpect(jsonPath("$.inPortfolio").value(false))
                .andExpect(jsonPath("$.reason").value("RANK_OUT"))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.matchesPattern("전기·전자 \\d+개 중 \\d+위라 순위 밖이에요")));
        mvc.perform(get("/api/recommendations/" + id + "/why-not/" + PICKED).with(user(me)))
                .andExpect(jsonPath("$.inPortfolio").value(true))
                .andExpect(jsonPath("$.text").value("이번 추천에 들어 있어요"));
        mvc.perform(get("/api/recommendations/" + id + "/why-not/999999").with(user(me)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STOCK_NOT_FOUND"));
    }

    @Test
    void T_INT_08_09_매수_완료는_최신_버전으로만_하고_포트폴리오에_합산된다() throws Exception {
        long id = idOf(create(Map.of()));
        edit(id, Map.of("method", "INVERSE_VOL")).andExpect(status().isCreated());

        purchase(id, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STALE_RECOMMENDATION"));
        purchase(id, 2).andExpect(status().isOk()).andExpect(jsonPath("$.purchased").value(true));

        // 기존 삼성전자 20주는 그대로(EXISTING), 추천 4종목은 SERVICE로 평균가와 함께
        List<Map<String, Object>> holdings = jdbc.queryForList(
                "SELECT h.code, h.quantity, h.avg_price, h.origin FROM holding h JOIN portfolio p ON p.id = h.portfolio_id "
                        + "WHERE p.user_id = ? ORDER BY h.code", me.id());
        assertThat(holdings).hasSize(5);
        assertThat(holdings).filteredOn(h -> h.get("code").equals("005930")).singleElement()
                .satisfies(h -> assertThat(h).containsEntry("quantity", 20).containsEntry("origin", "EXISTING"));
        assertThat(holdings).filteredOn(h -> h.get("origin").equals("SERVICE")).hasSize(4)
                .allSatisfy(h -> assertThat((Double) h.get("avg_price")).isPositive());

        Map<String, Object> pf = jdbc.queryForMap(
                "SELECT bond_units, cash, purchased_at, holdings_changed_at FROM portfolio WHERE user_id = ?", me.id());
        assertThat((Double) pf.get("bond_units")).isEqualTo(104457.704917);
        assertThat(pf).containsEntry("cash", 11_716_650L);
        assertThat(((java.sql.Date) pf.get("purchased_at")).toLocalDate()).isEqualTo(LocalDate.of(2026, 8, 19));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM target_weight t JOIN portfolio p ON p.id = t.portfolio_id "
                + "WHERE p.user_id = ?", Integer.class, me.id())).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT purchased_version FROM recommendation WHERE id = ?", Integer.class, id))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT pending_invest_amount FROM onboarding_state WHERE user_id = ?",
                Long.class, me.id())).isNull();
        mvc.perform(get("/api/me").with(user(me))).andExpect(jsonPath("$.purchased").value(true));

        // 다시 매수·수정은 막는다
        purchase(id, 2).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_PURCHASED"));
        edit(id, Map.of("method", "EQUAL")).andExpect(status().isConflict());

        // 매수 후에는 추가 투자 금액이 꼭 있어야 하고 2,000만 원 이상이어야 한다 (FR-PF-05)
        create(Map.of()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.detail.reason").value("REQUIRED"));
        create(Map.of("investAmount", 19_999_999)).andExpect(status().isUnprocessableEntity());
        create(Map.of("investAmount", 20_000_000)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.investAmount").value(20_000_000));
    }

    @Test
    void 더_최근_추천이_있으면_예전_추천으로는_매수할_수_없다() throws Exception {
        long first = idOf(create(Map.of()));
        long second = idOf(create(Map.of()));
        mvc.perform(get("/api/recommendations/" + first).with(user(me)))
                .andExpect(jsonPath("$.canPurchase").value(false));
        purchase(first, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STALE_RECOMMENDATION"));
        purchase(second, 1).andExpect(status().isOk());
        // 추천 결과 화면
        mvc.perform(get("/recommendations/" + second).with(user(me)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(not(org.hamcrest.Matchers.containsString("준비 중"))));
    }

    // ------------------------------------------------------------ 도우미

    private ResultActions create(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/recommendations").with(user(me)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions edit(long id, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/recommendations/" + id + "/versions").with(user(me)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions purchase(long id, int version) throws Exception {
        return mvc.perform(post("/api/recommendations/" + id + "/purchase").with(user(me)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"));
    }

    private long idOf(ResultActions r) throws Exception {
        JsonNode body = json.readTree(r.andReturn().getResponse().getContentAsString());
        return body.path("recommendationId").asLong();
    }
}
