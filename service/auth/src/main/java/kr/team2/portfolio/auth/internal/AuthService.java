package kr.team2.portfolio.auth.internal;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import kr.team2.portfolio.auth.api.AuthFacade;
import kr.team2.portfolio.auth.api.AuthenticatedUser;
import kr.team2.portfolio.auth.api.CurrentUser;
import kr.team2.portfolio.common.BusinessException;
import kr.team2.portfolio.common.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원가입과 로그인 사용자 조회. Spring Security가 이 {@link UserDetailsService}로 폼 로그인을 처리한다.
 */
@Service
class AuthService implements AuthFacade, UserDetailsService {

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Autowired
    AuthService(UserAccountRepository users, PasswordEncoder passwordEncoder) {
        this(users, passwordEncoder, Clock.systemUTC());
    }

    AuthService(UserAccountRepository users, PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Override
    @Transactional
    public CurrentUser register(String username, String rawPassword) {
        if (!UsernameRules.validUsername(username)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of("field", "username"));
        }
        if (!UsernameRules.validPassword(rawPassword)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of("field", "password"));
        }
        String normalized = UsernameRules.normalize(username);
        if (users.existsByUsername(normalized)) {
            throw new BusinessException(ErrorCode.USERNAME_TAKEN);
        }
        try {
            UserAccount saved = users.saveAndFlush(new UserAccount(
                    normalized, passwordEncoder.encode(rawPassword), LocalDateTime.now(clock)));
            return new CurrentUser(saved.getId(), saved.getUsername());
        } catch (DataIntegrityViolationException e) {
            // 두 요청이 동시에 같은 아이디로 가입한 경우 (UNIQUE 제약)
            throw new BusinessException(ErrorCode.USERNAME_TAKEN);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        return users.findByUsername(UsernameRules.normalize(username))
                .map(u -> new AuthenticatedUser(u.getId(), u.getUsername(), u.getPasswordHash()))
                .orElseThrow(() -> new UsernameNotFoundException("no such user"));
    }
}
