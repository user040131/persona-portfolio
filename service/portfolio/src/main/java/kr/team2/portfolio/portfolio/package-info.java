/**
 * 보유·채권 단위·현금·목표 비중, 평가 (docs/03 §6.4, §7).
 * 다른 모듈은 {@code api} 패키지만 쓸 수 있다. {@code internal}은 이 모듈 전용이다.
 */
@ApplicationModule(displayName = "Portfolio", allowedDependencies = {"common", "quantclient :: api", "profile :: api"})
package kr.team2.portfolio.portfolio;

import org.springframework.modulith.ApplicationModule;
