package kr.team2.portfolio.auth.internal;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 아이디·비밀번호 규칙 (01 FR-AUTH-01).
 * <ul>
 *   <li>아이디: 영문·숫자 4~20자. 대소문자를 구분하지 않도록 소문자로 저장한다
 *       (MySQL 기본 정렬도 대소문자를 구분하지 않는다).</li>
 *   <li>비밀번호: 8~64자. BCrypt는 72바이트까지만 쓰므로 상한을 둔다.</li>
 * </ul>
 */
final class UsernameRules {

    static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9]{4,20}$");
    static final int PASSWORD_MIN = 8;
    static final int PASSWORD_MAX = 64;

    private UsernameRules() {
    }

    static String normalize(String username) {
        return username == null ? null : username.trim().toLowerCase(Locale.ROOT);
    }

    static boolean validUsername(String username) {
        return username != null && USERNAME.matcher(username.trim()).matches();
    }

    static boolean validPassword(String password) {
        return password != null && password.length() >= PASSWORD_MIN && password.length() <= PASSWORD_MAX;
    }
}
