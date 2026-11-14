/**
 * 백테스트 작업 요청·소유권·결과 전달 (docs/03 §6.6).
 * 다른 모듈은 {@code api} 패키지만 쓸 수 있다. {@code internal}은 이 모듈 전용이다.
 */
@ApplicationModule(displayName = "Backtest", allowedDependencies = {"common", "quantclient :: api", "profile :: api"})
package kr.team2.portfolio.backtest;

import org.springframework.modulith.ApplicationModule;
