package kr.team2.portfolio.quantclient.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 {@code quant.*} (docs/03 §5.4).
 *
 * @param stubEnabled true면 엔진을 부르지 않고 대역 데이터를 쓴다 (엔진 없이 개발할 때)
 */
@ConfigurationProperties(prefix = "quant")
record QuantProperties(String baseUrl, String serviceKey, boolean stubEnabled, Timeouts timeouts) {

    record Timeouts(Duration connect, Duration defaultRead, Duration compute,
                    Duration evaluate, Duration historyCard) {
    }
}
