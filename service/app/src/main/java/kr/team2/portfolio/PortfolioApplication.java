package kr.team2.portfolio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 서비스 실행 진입점. 하위 패키지 하나하나가 Spring Modulith 모듈이다
 * (common, quantclient, auth, profile, portfolio, recommendation, alert, backtest, web, app).
 */
@SpringBootApplication
public class PortfolioApplication {

    public static void main(String[] args) {
        SpringApplication.run(PortfolioApplication.class, args);
    }
}
