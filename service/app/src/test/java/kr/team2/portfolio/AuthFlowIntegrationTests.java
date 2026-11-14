package kr.team2.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 가입 → 로그인 → /api/me → 로그아웃을 실제 MySQL로 끝까지 확인한다 (01 FR-AUTH-01~05).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowIntegrationTests extends MySqlIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 가입하고_로그인하면_세션으로_api_me를_부를_수_있다() throws Exception {
        signup("FlowUser1", "password123")
                .andExpect(redirectedUrl("/login?signup"));

        // 비밀번호는 원문이 아니라 BCrypt 해시로 저장된다
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE username = 'flowuser1'", String.class);
        assertThat(hash).startsWith("$2").doesNotContain("password123");

        MvcResult login = mvc.perform(post("/login").with(csrf())
                        .param("username", "FLOWUSER1")          // 대소문자 상관없이 로그인
                        .param("password", "password123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mvc.perform(get("/api/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("flowuser1"))
                .andExpect(jsonPath("$.id").isNumber());

        // 갓 가입한 사용자는 온보딩부터
        mvc.perform(get("/").session(session))
                .andExpect(redirectedUrl("/onboarding"));

        mvc.perform(post("/logout").with(csrf()).session(session))
                .andExpect(redirectedUrl("/login?logout"));
        assertThat(session.isInvalid()).as("로그아웃하면 세션이 무효화된다").isTrue();
    }

    @Test
    void 비밀번호가_틀리면_로그인_화면에_오류를_보여준다() throws Exception {
        signup("flowuser2", "password123");

        mvc.perform(post("/login").with(csrf())
                        .param("username", "flowuser2")
                        .param("password", "wrong-password"))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void 같은_아이디로_다시_가입하면_막는다() throws Exception {
        signup("flowuser3", "password123");

        signup("FlowUser3", "password456")
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("이미 쓰고 있는 아이디예요.")));
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = 'flowuser3'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.ResultActions signup(String username, String password) throws Exception {
        return mvc.perform(post("/signup").with(csrf())
                .param("username", username)
                .param("password", password)
                .param("passwordConfirm", password));
    }
}
