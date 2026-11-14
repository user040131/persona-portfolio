package kr.team2.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import kr.team2.portfolio.auth.api.AuthFacade;
import kr.team2.portfolio.auth.api.AuthenticatedUser;
import kr.team2.portfolio.auth.api.CurrentUser;
import kr.team2.portfolio.quantclient.api.BacktestRequest;
import kr.team2.portfolio.quantclient.api.QuantClient;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * 백테스트 작업 (01 FR-BT-13·14, 03 §6.6)과 과거 성과 카드 (FR-REC-14).
 * 엔진 대신 대역(실제 엔진 2024~2026 결과)을 쓰고, 엔진에 보내는 요청은 스파이로 확인한다.
 */
@SpringBootTest(properties = "quant.stub-enabled=true")
@AutoConfigureMockMvc
class BacktestIntegrationTests extends MySqlIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthFacade auth;

    @Autowired
    JsonMapper json;

    @MockitoSpyBean
    QuantClient quant;

    AuthenticatedUser me;

    @BeforeEach
    void 사용자() {
        me = newUser();
    }

    private AuthenticatedUser newUser() {
        CurrentUser u = auth.register("bt" + System.nanoTime() % 1_000_000_000L, "password123");
        return new AuthenticatedUser(u.id(), u.username(), "unused");
    }

    @Test
    void FR_BT_14_대표_프로필_8개의_설정값을_계산해_보낸다() throws Exception {
        create(Map.of()).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.startYear").value(2015))
                .andExpect(jsonPath("$.tradingCostRate").value(0.003))
                .andExpect(jsonPath("$.experiments").value(Matchers.contains("A", "B", "C", "D")));

        ArgumentCaptor<BacktestRequest> req = ArgumentCaptor.forClass(BacktestRequest.class);
        verify(quant).createBacktest(req.capture());
        BacktestRequest r = req.getValue();
        assertThat(r.profiles()).extracting(BacktestRequest.Profile::key).containsExactly(
                "STABLE_REP", "NEUTRAL_REP", "AGGRESSIVE_REP", "LIMIT_NONE", "LIMIT_20", "LIMIT_10", "LIMIT_05", "LIMIT_03");
        var byKey = r.profiles().stream().collect(java.util.stream.Collectors.toMap(
                BacktestRequest.Profile::key, BacktestRequest.Profile::settings));
        assertThat(byKey.get("STABLE_REP").grade()).isEqualTo("STABLE");
        assertThat(byKey.get("STABLE_REP").lossLimit()).isEqualTo(-0.05);
        assertThat(byKey.get("AGGRESSIVE_REP").grade()).isEqualTo("AGGRESSIVE");
        assertThat(byKey.get("AGGRESSIVE_REP").lossLimit()).isNull();
        assertThat(byKey.get("LIMIT_03").lossLimit()).isEqualTo(-0.03);
        assertThat(byKey.get("LIMIT_NONE").lossLimit()).isNull();
        assertThat(r.multiWeights()).containsEntry("VALUE", 0.30).containsEntry("DIVIDEND", 0.0);
    }

    @Test
    void 실험_D가_없으면_프로필을_보내지_않는다() throws Exception {
        create(Map.of("startYear", 2020, "experiments", java.util.List.of("c", "A"), "tradingCostRate", 0.001))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.experiments").value(Matchers.contains("A", "C")));
        ArgumentCaptor<BacktestRequest> req = ArgumentCaptor.forClass(BacktestRequest.class);
        verify(quant).createBacktest(req.capture());
        assertThat(req.getValue().profiles()).isEmpty();
        assertThat(req.getValue().startYear()).isEqualTo(2020);

        create(Map.of("experiments", java.util.List.of("E"))).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void 작업은_만든_사람만_보고_결과를_전달한다() throws Exception {
        String jobId = json.readTree(create(Map.of()).andReturn().getResponse().getContentAsString())
                .path("jobId").asString();

        mvc.perform(get("/api/backtests").with(user(me)))
                .andExpect(jsonPath("$[0].jobId").value(jobId));
        mvc.perform(get("/api/backtests/" + jobId).with(user(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.result.strategies.length()").value(13))
                .andExpect(jsonPath("$.result.limitations").value(Matchers.hasItem("SURVIVORSHIP")));
        mvc.perform(get("/api/backtests").with(user(me)))                 // 상태를 확인하면 기록된다
                .andExpect(jsonPath("$[0].status").value("DONE"));
        mvc.perform(get("/api/backtests/" + jobId + "/nav").param("freq", "W").with(user(me)))
                .andExpect(jsonPath("$.freq").value("W"))
                .andExpect(jsonPath("$.strategies.KOSPI.nav").isArray());
        mvc.perform(get("/api/backtests/" + jobId + "/holdings").param("strategy", "NEUTRAL_REP").with(user(me)))
                .andExpect(jsonPath("$.holdings[?(@.asset == 'BOND')].name").value(Matchers.hasItem("채권")))
                .andExpect(jsonPath("$.holdings[0].name").exists());
        mvc.perform(get("/api/backtests/" + jobId + "/export.csv").with(user(me)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", Matchers.containsString("attachment")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"));
        mvc.perform(get("/backtests/" + jobId).with(user(me))).andExpect(status().isOk());
        mvc.perform(get("/backtests").with(user(me))).andExpect(status().isOk());

        AuthenticatedUser other = newUser();
        mvc.perform(get("/api/backtests/" + jobId).with(user(other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BACKTEST_NOT_FOUND"));
        mvc.perform(get("/api/backtests/" + jobId + "/export.csv").with(user(other))).andExpect(status().isNotFound());
    }

    @Test
    void FR_REC_14_추천_화면의_과거_성과_카드() throws Exception {
        mvc.perform(post("/api/onboarding").with(user(me)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(OnboardingIntegrationTests.goldenRequest())))
                .andExpect(status().isCreated());
        long id = json.readTree(mvc.perform(post("/api/recommendations").with(user(me)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getContentAsString()).path("recommendationId").asLong();

        mvc.perform(get("/api/recommendations/" + id + "/history-card").with(user(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.start").value("2015-04-01"))
                .andExpect(jsonPath("$.worst_year.year").isNumber());
        verify(quant).historyCard(any(), org.mockito.ArgumentMatchers.eq(2015));
    }

    private ResultActions create(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/backtests").with(user(me)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
}
