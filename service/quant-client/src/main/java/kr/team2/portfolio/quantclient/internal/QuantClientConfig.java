package kr.team2.portfolio.quantclient.internal;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(QuantProperties.class)
class QuantClientConfig {

    /** 서비스 간 공유 비밀값 헤더 (docs/02 §5). */
    static final String SERVICE_KEY_HEADER = "X-Service-Key";

    /** 짧은 호출용 (상태·종목 목록, 읽기 5초). */
    @Bean
    RestClient quantRestClient(RestClient.Builder builder, QuantProperties props) {
        return build(builder, props.baseUrl(), props.serviceKey(), props.timeouts().connect(),
                props.timeouts().defaultRead());
    }

    /** 계산 호출용 (추천 계산, 읽기 15초). */
    @Bean
    RestClient quantComputeClient(RestClient.Builder builder, QuantProperties props) {
        return build(builder, props.baseUrl(), props.serviceKey(), props.timeouts().connect(),
                props.timeouts().compute());
    }

    /** 포트폴리오 평가용 (읽기 5초). */
    @Bean
    RestClient quantEvaluateClient(RestClient.Builder builder, QuantProperties props) {
        return build(builder, props.baseUrl(), props.serviceKey(), props.timeouts().connect(),
                props.timeouts().evaluate());
    }

    /** 과거 성과 카드용 (처음 한 번은 백테스트를 돌리므로 읽기 30초). */
    @Bean
    RestClient quantHistoryClient(RestClient.Builder builder, QuantProperties props) {
        return build(builder, props.baseUrl(), props.serviceKey(), props.timeouts().connect(),
                props.timeouts().historyCard());
    }

    /**
     * 엔진 호출 클라이언트. JDK HttpClient는 기본으로 HTTP/2 업그레이드(h2c)를 시도하는데,
     * 엔진(uvicorn)은 이를 지원하지 않아 본문이 있는 POST가 400으로 깨진다. 그래서 HTTP/1.1로 고정한다.
     */
    static RestClient build(RestClient.Builder builder, String baseUrl, String serviceKey,
                            Duration connectTimeout, Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return builder.clone()
                .baseUrl(baseUrl)
                .defaultHeader(SERVICE_KEY_HEADER, serviceKey)
                .requestFactory(factory)
                .build();
    }
}
