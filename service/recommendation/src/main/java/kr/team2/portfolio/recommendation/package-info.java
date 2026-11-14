/**
 * 추천 요청·버전·문구·왜 없나요·매수 완료 (docs/03 §6.2~6.4).
 * 다른 모듈은 {@code api} 패키지만 쓸 수 있다. {@code internal}은 이 모듈 전용이다.
 */
@ApplicationModule(displayName = "Recommendation", allowedDependencies = {"common", "quantclient :: api", "profile :: api", "portfolio :: api"})
package kr.team2.portfolio.recommendation;

import org.springframework.modulith.ApplicationModule;
