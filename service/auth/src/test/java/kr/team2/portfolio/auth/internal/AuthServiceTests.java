package kr.team2.portfolio.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import kr.team2.portfolio.auth.api.AuthenticatedUser;
import kr.team2.portfolio.auth.api.CurrentUser;
import kr.team2.portfolio.common.BusinessException;
import kr.team2.portfolio.common.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class AuthServiceTests {

    private final UserAccountRepository repo = mock(UserAccountRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4); // 테스트는 빠르게
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC);
    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(repo, encoder, clock);
        when(repo.saveAndFlush(any())).thenAnswer(inv -> {
            UserAccount u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", 7L);
            return u;
        });
    }

    @Test
    void 가입하면_아이디는_소문자로_비밀번호는_해시로_저장한다() {
        CurrentUser user = service.register("Team2User", "password123");

        assertThat(user).isEqualTo(new CurrentUser(7L, "team2user"));
        verify(repo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(u ->
                u.getUsername().equals("team2user")
                        && !u.getPasswordHash().equals("password123")
                        && encoder.matches("password123", u.getPasswordHash())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "has space", "한글아이디", "a_b_c_d", "abcdefghijklmnopqrstu"})
    void 아이디_규칙에_맞지_않으면_거절한다(String username) {
        assertThatThrownBy(() -> service.register(username, "password123"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void 비밀번호가_8자_미만이면_거절한다() {
        assertThatThrownBy(() -> service.register("team2user", "short"))
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void 이미_있는_아이디는_대소문자가_달라도_거절한다() {
        when(repo.existsByUsername("team2user")).thenReturn(true);

        assertThatThrownBy(() -> service.register("TEAM2USER", "password123"))
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.USERNAME_TAKEN);
    }

    @Test
    void 동시에_가입해_UNIQUE_제약에_걸려도_USERNAME_TAKEN() {
        // when(...)으로 다시 스텁하면 setUp의 응답이 null 인자로 먼저 실행되므로 doThrow를 쓴다
        doThrow(new DataIntegrityViolationException("dup")).when(repo).saveAndFlush(any());

        assertThatThrownBy(() -> service.register("team2user", "password123"))
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.USERNAME_TAKEN);
    }

    @Test
    void 로그인_조회는_대소문자를_구분하지_않고_id를_담는다() {
        UserAccount stored = new UserAccount("team2user", "hash", null);
        ReflectionTestUtils.setField(stored, "id", 7L);
        when(repo.findByUsername("team2user")).thenReturn(Optional.of(stored));

        AuthenticatedUser user = (AuthenticatedUser) service.loadUserByUsername("Team2User");

        assertThat(user.id()).isEqualTo(7L);
        assertThat(user.getUsername()).isEqualTo("team2user");
        assertThat(user.getPassword()).isEqualTo("hash");
    }

    @Test
    void 없는_아이디는_UsernameNotFound() {
        when(repo.findByUsername("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("nobody"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
