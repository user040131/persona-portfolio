package kr.team2.portfolio.auth.api;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 세션에 들어가는 로그인 사용자. 컨트롤러에서 {@code @AuthenticationPrincipal AuthenticatedUser}로 받아
 * {@link #id()}로 다른 모듈을 호출한다.
 */
public final class AuthenticatedUser implements UserDetails {

    private static final List<GrantedAuthority> AUTHORITIES = List.of(new SimpleGrantedAuthority("ROLE_USER"));

    private final long id;
    private final String username;
    private final String passwordHash;

    public AuthenticatedUser(long id, String username, String passwordHash) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
    }

    public long id() {
        return id;
    }

    public CurrentUser toCurrentUser() {
        return new CurrentUser(id, username);
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return AUTHORITIES;
    }
}
