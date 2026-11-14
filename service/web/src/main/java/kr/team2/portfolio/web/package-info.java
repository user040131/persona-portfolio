/**
 * 화면(Thymeleaf)과 JSON API 컨트롤러 (docs/02 §3.4, 03 §7).
 * 업무 로직은 두지 않고 각 모듈의 {@code api} 패키지만 호출한다.
 */
@ApplicationModule(displayName = "Web", allowedDependencies = {
        "common",
        "quantclient :: api",
        "auth :: api",
        "profile :: api",
        "portfolio :: api",
        "recommendation :: api",
        "alert :: api",
        "backtest :: api"
})
package kr.team2.portfolio.web;

import org.springframework.modulith.ApplicationModule;
