package kr.team2.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import tools.jackson.databind.json.JsonMapper;

/**
 * 온보딩 → 프로필·포트폴리오 저장 → 재진단을 실제 MySQL로 확인한다 (01 FR-ONB, 03 §8.9 T-INT-01·02).
 * 엔진 대신 대역 종목 목록(quant.stub-enabled)을 쓴다.
 */
@SpringBootTest(properties = "quant.stub-enabled=true")
@AutoConfigureMockMvc
class OnboardingIntegrationTests extends MySqlIntegrationTest {

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
    void 새_사용자() {
        String name = "onb" + System.nanoTime() % 1_000_000_000L;
        CurrentUser u = auth.register(name, "password123");
        me = new AuthenticatedUser(u.id(), u.username(), "unused");
    }

    /** 01 FR-ONB 골든 프로필 + 삼성전자 20주, 같은 코드 한 번 더(합쳐져야 함). */
    static Map<String, Object> goldenRequest() {
        return Map.of(
                "investAmount", 30_000_000,
                "q", List.of(3, 3, 3, 3, 3, 3, 3),
                "a2", 2, "a3", false, "a4", 3, "a5", 4,
                "a6", List.of("DIVIDEND"), "a7", List.of(),
                "holdings", List.of(
                        Map.of("code", "005930", "quantity", 15),
                        Map.of("code", "005930", "quantity", 5)));
    }

    @Test
    void 온보딩하면_골든_프로필과_포트폴리오가_저장된다() throws Exception {
        onboard(goldenRequest())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.totalScore").value(14))
                .andExpect(jsonPath("$.grade").value("NEUTRAL"))
                .andExpect(jsonPath("$.gradeLabel").value("위험중립형"))
                .andExpect(jsonPath("$.settings.lossLimit").value(-0.10))
                .andExpect(jsonPath("$.settings.minCash").value(0.15))
                .andExpect(jsonPath("$.settings.stockCap").value(0.55))
                .andExpect(jsonPath("$.settings.nStocks").value(15))
                .andExpect(jsonPath("$.settings.factorWeights.VALUE").value(0.2727))
                .andExpect(jsonPath("$.settings.factorWeights.DIVIDEND").value(0.0909))
                .andExpect(jsonPath("$.settings.defaultMethod").value("INVERSE_VOL"));

        assertThat(jdbc.queryForObject("SELECT pending_invest_amount FROM onboarding_state WHERE user_id = ?",
                Long.class, me.id())).isEqualTo(30_000_000L);
        // 같은 종목코드는 합쳐서 기존 보유(EXISTING)로 저장
        Map<String, Object> holding = jdbc.queryForMap(
                "SELECT h.code, h.quantity, h.origin FROM holding h JOIN portfolio p ON p.id = h.portfolio_id WHERE p.user_id = ?",
                me.id());
        assertThat(holding).containsEntry("code", "005930").containsEntry("quantity", 20).containsEntry("origin", "EXISTING");

        mvc.perform(get("/api/me").with(user(me))).andExpect(jsonPath("$.onboarded").value(true));
        mvc.perform(get("/").with(user(me))).andExpect(redirectedUrl("/profile"));
        mvc.perform(get("/profile").with(user(me)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("위험중립형")))
                .andExpect(content().string(containsString("삼성전자")))
                .andExpect(content().string(containsString("30,000,000원")));
    }

    @Test
    void 두_번_온보딩하면_409() throws Exception {
        onboard(goldenRequest()).andExpect(status().isCreated());
        onboard(goldenRequest())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_ONBOARDED"));
    }

    @Test
    void 최소_금액보다_적으면_422이고_아무것도_저장하지_않는다() throws Exception {
        Map<String, Object> req = new java.util.HashMap<>(goldenRequest());
        req.put("investAmount", 19_999_999);
        onboard(req)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM profile WHERE user_id = ?", Integer.class, me.id())).isZero();
    }

    @Test
    void 없는_종목코드면_422_UNKNOWN_STOCK이고_롤백된다() throws Exception {
        Map<String, Object> req = new java.util.HashMap<>(goldenRequest());
        req.put("holdings", List.of(Map.of("code", "999999", "quantity", 1)));
        onboard(req)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("UNKNOWN_STOCK"))
                .andExpect(jsonPath("$.error.detail.codes[0]").value("999999"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM onboarding_state WHERE user_id = ?", Integer.class, me.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portfolio WHERE user_id = ?", Integer.class, me.id())).isZero();
    }

    @Test
    void 설문이_잘못되면_422_INVALID_SURVEY() throws Exception {
        Map<String, Object> req = new java.util.HashMap<>(goldenRequest());
        req.put("a6", List.of("ANY", "VALUE"));
        onboard(req)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_SURVEY"));
    }

    @Test
    void 재진단하면_새_버전이_현재_프로필이_된다() throws Exception {
        onboard(goldenRequest()).andExpect(status().isCreated());

        Map<String, Object> answers = Map.of(
                "q", List.of(1, 1, 1, 1, 1, 1, 1), "a2", 1, "a3", true, "a4", 1, "a5", 1,
                "a6", List.of("ANY"), "a7", List.of("은행"));
        mvc.perform(put("/api/profile/survey").with(user(me)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(answers)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.grade").value("AGGRESSIVE"))
                .andExpect(jsonPath("$.settings.lossLimit").doesNotExist())
                .andExpect(jsonPath("$.settings.excludedSectors[0]").value("은행"));

        assertThat(jdbc.queryForList("SELECT version FROM profile WHERE user_id = ? AND is_current = 1",
                Integer.class, me.id())).containsExactly(2);
        // 보유와 투자 금액은 그대로
        assertThat(jdbc.queryForObject("SELECT pending_invest_amount FROM onboarding_state WHERE user_id = ?",
                Long.class, me.id())).isEqualTo(30_000_000L);
    }

    @Test
    void 설문_정의와_종목_검색() throws Exception {
        mvc.perform(get("/api/onboarding/survey").with(user(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.survey.questions.length()").value(7))
                .andExpect(jsonPath("$.survey.extras.length()").value(6))
                .andExpect(jsonPath("$.minInvestAmount").value(20_000_000))
                .andExpect(jsonPath("$.sectors.length()").value(27));
        mvc.perform(get("/api/stocks").param("q", "삼성전자").with(user(me)))
                .andExpect(jsonPath("$[0].code").value("005930"))
                .andExpect(jsonPath("$[0].lastClose").value(247500.0));
    }

    private ResultActions onboard(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/onboarding").with(user(me)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }
}
