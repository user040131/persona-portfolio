/**
 * 온보딩·설문·프로필 설정값 계산 (docs/03 §6.1).
 * 다른 모듈은 {@code api} 패키지만 쓸 수 있다. {@code internal}은 이 모듈 전용이다.
 */
@ApplicationModule(displayName = "Profile", allowedDependencies = {"common", "quantclient :: api"})
package kr.team2.portfolio.profile;

import org.springframework.modulith.ApplicationModule;
