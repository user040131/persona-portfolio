package kr.team2.portfolio.app;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.team2.portfolio.alert.api.AlertFacade;
import kr.team2.portfolio.auth.api.AuthFacade;
import kr.team2.portfolio.backtest.api.BacktestFacade;
import kr.team2.portfolio.auth.api.AuthenticatedUser;
import kr.team2.portfolio.auth.api.CurrentUser;
import kr.team2.portfolio.common.BusinessException;
import kr.team2.portfolio.common.ErrorCode;
import kr.team2.portfolio.portfolio.api.PortfolioFacade;
import kr.team2.portfolio.profile.api.ProfileFacade;
import kr.team2.portfolio.quantclient.api.StockCatalog;
import kr.team2.portfolio.recommendation.api.RecommendationFacade;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 보안 규칙과 회원가입 화면 (01 FR-AUTH). DB 없이 웹 계층만 띄운다.
 */
@WebMvcTest
@Import(SecurityConfig.class)
class SecurityWebTests {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AuthFacade auth;

    @MockitoBean
    ProfileFacade profiles;

    @MockitoBean
    PortfolioFacade portfolios;

    @MockitoBean
    StockCatalog catalog;

    @MockitoBean
    RecommendationFacade recommendations;

    @MockitoBean
    AlertFacade alerts;

    @MockitoBean
    BacktestFacade backtests;

    private static final AuthenticatedUser TEAM2 = new AuthenticatedUser(7L, "team2user", "hash");

    @Test
    void 로그인_화면은_누구나_볼_수_있다() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("로그인")));
    }

    @Test
    void 로그인하지_않으면_페이지는_로그인_화면으로_보낸다() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void 로그인하지_않으면_API는_401_JSON을_준다() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void 온보딩_전이면_홈은_온보딩으로_보낸다() throws Exception {
        when(profiles.isOnboarded(7L)).thenReturn(false);
        mvc.perform(get("/").with(user(TEAM2)))
                .andExpect(redirectedUrl("/onboarding"));
    }

    @Test
    void 온보딩_후면_홈은_프로필로_보낸다() throws Exception {
        when(profiles.isOnboarded(7L)).thenReturn(true);
        mvc.perform(get("/").with(user(TEAM2)))
                .andExpect(redirectedUrl("/profile"));
    }

    @Test
    void 추천이_있으면_홈은_최근_추천으로_보낸다() throws Exception {
        when(profiles.isOnboarded(7L)).thenReturn(true);
        when(recommendations.latestId(7L)).thenReturn(java.util.Optional.of(42L));
        mvc.perform(get("/").with(user(TEAM2)))
                .andExpect(redirectedUrl("/recommendations/42"));
    }

    @Test
    void 온보딩_화면은_로그인하면_볼_수_있다() throws Exception {
        mvc.perform(get("/onboarding").with(user(TEAM2)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("투자 성향을 알려 주세요")));
    }

    @Test
    void api_me는_로그인한_사용자를_준다() throws Exception {
        mvc.perform(get("/api/me").with(user(TEAM2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.username").value("team2user"))
                .andExpect(jsonPath("$.onboarded").value(false));
    }

    @Test
    void 회원가입_화면은_누구나_볼_수_있다() throws Exception {
        mvc.perform(get("/signup"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("회원가입")));
    }

    @Test
    void 가입에_성공하면_로그인_화면으로_보낸다() throws Exception {
        when(auth.register("team2user", "password123")).thenReturn(new CurrentUser(7L, "team2user"));

        mvc.perform(post("/signup").with(csrf())
                        .param("username", "team2user")
                        .param("password", "password123")
                        .param("passwordConfirm", "password123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?signup"));
    }

    @Test
    void 입력이_규칙에_어긋나면_폼에_오류를_보여준다() throws Exception {
        mvc.perform(post("/signup").with(csrf())
                        .param("username", "ab")
                        .param("password", "short")
                        .param("passwordConfirm", "different"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("아이디는 영문·숫자 4~20자예요.")))
                .andExpect(content().string(containsString("비밀번호는 8~64자예요.")))
                .andExpect(content().string(containsString("비밀번호가 서로 달라요.")));
        verify(auth, never()).register(any(), any());
    }

    @Test
    void 이미_있는_아이디면_폼에_알려준다() throws Exception {
        when(auth.register(any(), any())).thenThrow(new BusinessException(ErrorCode.USERNAME_TAKEN));

        mvc.perform(post("/signup").with(csrf())
                        .param("username", "team2user")
                        .param("password", "password123")
                        .param("passwordConfirm", "password123"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("이미 쓰고 있는 아이디예요.")));
    }

    @Test
    void CSRF_토큰이_없으면_가입을_막는다() throws Exception {
        mvc.perform(post("/signup")
                        .param("username", "team2user")
                        .param("password", "password123")
                        .param("passwordConfirm", "password123"))
                .andExpect(status().isForbidden());
        verify(auth, never()).register(any(), any());
    }
}
