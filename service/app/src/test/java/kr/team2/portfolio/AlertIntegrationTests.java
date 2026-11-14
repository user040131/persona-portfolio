package kr.team2.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import kr.team2.portfolio.alert.api.AlertFacade;
import kr.team2.portfolio.alert.api.AlertType;
import kr.team2.portfolio.alert.api.AlertView;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 알림 상태(docs/03 §6.5, T-SP-ALT-01·02)·닫기·리밸런싱 제안과 반영 (01 FR-ALT-01·08·09).
 * 엔진 대신 대역을 쓴다. 대역의 리밸런싱은 알림 종류와 관계없이 저장된 목표 비중으로 계산한다.
 */
@SpringBootTest(properties = "quant.stub-enabled=true")
@AutoConfigureMockMvc
class AlertIntegrationTests extends MySqlIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuthFacade auth;

    @Autowired
    AlertFacade alerts;

    @Autowired
    JsonMapper json;

    AuthenticatedUser me;

    @BeforeEach
    void 매수까지_마친_사용자() throws Exception {
        me = newUser();
        mvc.perform(post("/api/onboarding").with(user(me)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(OnboardingIntegrationTests.goldenRequest())))
                .andExpect(status().isCreated());
        String body = mvc.perform(post("/api/recommendations").with(user(me)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(body).path("recommendationId").asLong();
        mvc.perform(post("/api/recommendations/" + id + "/purchase").with(user(me)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk());
    }

    private AuthenticatedUser newUser() {
        CurrentUser u = auth.register("al" + System.nanoTime() % 1_000_000_000L, "password123");
        return new AuthenticatedUser(u.id(), u.username(), "unused");
    }

    @Test
    void 포트폴리오를_보면_알림이_열리고_다시_봐도_하나만_있다() throws Exception {
        // 기존 삼성전자 20주가 주식의 절반 → 종목 쏠림(R1)·업종 쏠림(R2)
        JsonNode first = portfolio();
        assertThat(types(first)).contains("R1:005930", "R2:전기·전자");
        JsonNode r1 = find(first, "R1");
        assertThat(r1.path("title").asString()).isEqualTo("종목 쏠림");
        assertThat(r1.path("text").asString()).matches("삼성전자이\\(가\\) 주식의 \\d+%예요 \\(한도 20%\\)");

        JsonNode second = portfolio();                       // T-SP-ALT-02: 같은 조건 2회 → 열린 알림 1건
        assertThat(find(second, "R1").path("alertId").asLong()).isEqualTo(r1.path("alertId").asLong());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert a JOIN portfolio p ON p.id = a.portfolio_id "
                + "WHERE p.user_id = ? AND a.type = 'R1'", Integer.class, me.id())).isEqualTo(1);

        mvc.perform(get("/api/alerts").with(user(me)))
                .andExpect(jsonPath("$[*].type").value(Matchers.hasItems("R1", "R2")));
    }

    @Test
    void 닫은_알림은_조건이_남아도_다시_뜨지_않고_조건이_사라지면_CLOSED() throws Exception {
        long r1 = find(portfolio(), "R1").path("alertId").asLong();
        mvc.perform(post("/api/alerts/" + r1 + "/dismiss").with(user(me)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"));
        assertThat(types(portfolio())).doesNotContain("R1:005930");   // FR-ALT-08
        assertThat(alertStatus(r1)).isEqualTo("DISMISSED");

        // T-SP-ALT-01: 삼성전자를 1주로 줄이면 R1 조건이 사라진다 → CLOSED
        patchPortfolio(Map.of("holdings", List.of(Map.of("code", "005930", "quantity", 1))))
                .andExpect(status().isOk());
        assertThat(alertStatus(r1)).isEqualTo("CLOSED");
        mvc.perform(post("/api/alerts/" + r1 + "/dismiss").with(user(me)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALERT_CLOSED"));

        // 조건이 다시 생기면 새 알림으로 연다
        patchPortfolio(Map.of("holdings", List.of(Map.of("code", "005930", "quantity", 20)))).andExpect(status().isOk());
        assertThat(find(portfolio(), "R1").path("alertId").asLong()).isNotEqualTo(r1);
    }

    @Test
    void 제안대로_하면_수량_목표가_바뀌고_알림이_닫힌다() throws Exception {
        // 삼성전자를 40주로 늘리면 목표에서 벌어진다(R3)
        JsonNode pf = json.readTree(patchPortfolio(Map.of("holdings", List.of(Map.of("code", "005930", "quantity", 40))))
                .andReturn().getResponse().getContentAsString());
        long r3 = pf.path("alerts").valueStream()
                .filter(a -> a.path("type").asString().equals("R3") && a.path("target").asString().equals("005930"))
                .findFirst().orElseThrow().path("alertId").asLong();

        mvc.perform(get("/api/alerts/" + r3 + "/suggestion").with(user(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alert.type").value("R3"))
                .andExpect(jsonPath("$.help").value(Matchers.startsWith("매수 완료 때 정한 목표 비중")))
                .andExpect(jsonPath("$.trades[0].asset").value("005930"))
                .andExpect(jsonPath("$.trades[0].name").value("삼성전자"))
                .andExpect(jsonPath("$.trades[0].side").value("SELL"))
                .andExpect(jsonPath("$.trades[0].sideLabel").value("팔기"))
                .andExpect(jsonPath("$.before.stock").exists())
                .andExpect(jsonPath("$.after.byAsset.stock").exists())
                .andExpect(jsonPath("$.canApply").value(true));

        mvc.perform(post("/api/alerts/" + r3 + "/apply").with(user(me)).with(csrf()))
                .andExpect(status().isOk());
        assertThat(alertStatus(r3)).isEqualTo("CLOSED");
        Integer qty = jdbc.queryForObject("SELECT h.quantity FROM holding h JOIN portfolio p ON p.id = h.portfolio_id "
                + "WHERE p.user_id = ? AND h.code = '005930'", Integer.class, me.id());
        assertThat(qty).isBetween(20, 25);                   // 목표 비중으로 되돌림 (총액이 늘어난 만큼 조금 더)
        assertThat(types(portfolio())).noneMatch(t -> t.startsWith("R3:"));

        mvc.perform(post("/api/alerts/" + r3 + "/apply").with(user(me)).with(csrf()))
                .andExpect(status().isConflict());
        mvc.perform(get("/alerts/" + r3).with(user(me))).andExpect(status().isOk());
    }

    @Test
    void R6_정기_점검은_연_1회_열리고_사용자가_닫을_때까지_남는다() throws Exception {
        JsonNode r6 = json.readTree("[{\"type\": \"R6\", \"target\": \"\", \"detail\": {\"year\": 2027}}]");
        List<AlertView> opened = alerts.sync(me.id(), r6);
        AlertView alert = opened.stream().filter(a -> a.type() == AlertType.R6).findFirst().orElseThrow();
        assertThat(alert.text()).isEqualTo("2027년 정기 점검 시기예요. 새 재무가 반영됐어요");
        assertThat(jdbc.queryForObject("SELECT last_r6_year FROM portfolio WHERE user_id = ?", Integer.class, me.id()))
                .isEqualTo(2027);

        // 엔진은 그해에 R6를 다시 내지 않지만, 알림은 남아 있어야 한다
        assertThat(alerts.sync(me.id(), json.readTree("[]"))).extracting(AlertView::type).contains(AlertType.R6);
        alerts.dismiss(me.id(), alert.alertId());
        assertThat(alertStatus(alert.alertId())).isEqualTo("CLOSED");   // DISMISSED로 두면 다음 해 R6가 막힌다
    }

    @Test
    void 다른_사용자의_알림은_404() throws Exception {
        long r1 = find(portfolio(), "R1").path("alertId").asLong();
        AuthenticatedUser other = newUser();
        mvc.perform(get("/api/alerts/" + r1 + "/suggestion").with(user(other)))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------ 도우미

    private JsonNode portfolio() throws Exception {
        return json.readTree(mvc.perform(get("/api/portfolio").with(user(me)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions patchPortfolio(Map<String, Object> body) throws Exception {
        return mvc.perform(patch("/api/portfolio").with(user(me)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private static List<String> types(JsonNode pf) {
        return pf.path("alerts").valueStream()
                .map(a -> a.path("type").asString() + ":" + a.path("target").asString()).toList();
    }

    private static JsonNode find(JsonNode pf, String type) {
        return pf.path("alerts").valueStream().filter(a -> a.path("type").asString().equals(type))
                .findFirst().orElseThrow(() -> new AssertionError(type + " 알림이 없어요: " + pf.path("alerts")));
    }

    private String alertStatus(long alertId) {
        return jdbc.queryForObject("SELECT status FROM alert WHERE id = ?", String.class, alertId);
    }
}
