/**
 * 회원가입·로그인 (docs/02 §3.5, 01 FR-AUTH).
 * 다른 모듈은 {@code api} 패키지만 쓸 수 있다. {@code internal}은 이 모듈 전용이다.
 */
@ApplicationModule(displayName = "Auth", allowedDependencies = {"common"})
package kr.team2.portfolio.auth;

import org.springframework.modulith.ApplicationModule;
