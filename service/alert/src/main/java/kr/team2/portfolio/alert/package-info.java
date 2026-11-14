/**
 * 리밸런싱 알림 상태와 제안 (docs/03 §6.5).
 * 다른 모듈은 {@code api} 패키지만 쓸 수 있다. {@code internal}은 이 모듈 전용이다.
 */
@ApplicationModule(displayName = "Alert", allowedDependencies = {"common", "quantclient :: api", "profile :: api", "portfolio :: api", "recommendation :: api"})
package kr.team2.portfolio.alert;

import org.springframework.modulith.ApplicationModule;
