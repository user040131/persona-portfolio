package kr.team2.portfolio.quantclient.internal;

import java.util.Map;
import java.util.function.Supplier;
import kr.team2.portfolio.common.BusinessException;
import kr.team2.portfolio.common.ErrorCode;
import kr.team2.portfolio.quantclient.api.BacktestRequest;
import kr.team2.portfolio.quantclient.api.ComputeRequest;
import kr.team2.portfolio.quantclient.api.EngineHealth;
import kr.team2.portfolio.quantclient.api.EvaluateRequest;
import kr.team2.portfolio.quantclient.api.QuantClient;
import kr.team2.portfolio.quantclient.api.RebalanceRequest;
import kr.team2.portfolio.quantclient.api.StockList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 엔진 호출. {@code quant.stub-enabled=true}이면 {@link StubQuantClient}가 대신한다. */
@Component
@ConditionalOnProperty(prefix = "quant", name = "stub-enabled", havingValue = "false", matchIfMissing = true)
class RestQuantClient implements QuantClient {

    private static final Logger log = LoggerFactory.getLogger(RestQuantClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient rest;
    private final RestClient compute;
    private final RestClient evaluate;
    private final RestClient history;

    @Autowired
    RestQuantClient(@Qualifier("quantRestClient") RestClient quantRestClient,
                    @Qualifier("quantComputeClient") RestClient quantComputeClient,
                    @Qualifier("quantEvaluateClient") RestClient quantEvaluateClient,
                    @Qualifier("quantHistoryClient") RestClient quantHistoryClient) {
        this.rest = quantRestClient;
        this.compute = quantComputeClient;
        this.evaluate = quantEvaluateClient;
        this.history = quantHistoryClient;
    }

    /** 테스트용: 한 클라이언트로 모두 부른다. */
    RestQuantClient(RestClient client) {
        this(client, client, client, client);
    }

    @Override
    public EngineHealth health() {
        EngineDtos.HealthResponse body = call(() -> rest.get().uri("/internal/v1/health")
                .retrieve().body(EngineDtos.HealthResponse.class));
        return new EngineHealth(body.status(), body.data_loaded());
    }

    @Override
    public StockList stocks() {
        return call(() -> rest.get().uri("/internal/v1/stocks")
                .retrieve().body(EngineDtos.StocksResponse.class)).toApi();
    }

    @Override
    public String computeRecommendation(ComputeRequest request) {
        String body = EngineJson.write(request);
        return call(() -> compute.post().uri("/internal/v1/recommendations/compute")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(String.class));
    }

    @Override
    public String evaluatePortfolio(EvaluateRequest request) {
        String body = EngineJson.write(request);
        return call(() -> evaluate.post().uri("/internal/v1/portfolio/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(String.class));
    }

    @Override
    public String rebalancePlan(RebalanceRequest request) {
        String body = EngineJson.write(request);
        return call(() -> compute.post().uri("/internal/v1/portfolio/rebalance-plan")   // R6은 추천 계산을 하므로 15초
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(String.class));
    }

    @Override
    public String historyCard(ComputeRequest.Settings settings, int startYear) {
        String body = EngineJson.write(java.util.Map.of("settings", settings, "start_year", startYear));
        return call(() -> history.post().uri("/internal/v1/history-card")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
    }

    @Override
    public String createBacktest(BacktestRequest request) {
        String body = EngineJson.write(request);
        String res = call(() -> rest.post().uri("/internal/v1/backtests")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
        return JSON.readTree(res).path("job_id").asString();
    }

    @Override
    public String backtestStatus(String jobId) {
        return call(() -> rest.get().uri("/internal/v1/backtests/{id}", jobId).retrieve().body(String.class));
    }

    @Override
    public String backtestNav(String jobId, String strategies, String freq) {
        return call(() -> rest.get().uri(b -> {
            b.path("/internal/v1/backtests/{id}/nav").queryParam("freq", freq == null ? "D" : freq);
            if (strategies != null && !strategies.isBlank()) {
                b.queryParam("strategies", strategies);
            }
            return b.build(jobId);
        }).retrieve().body(String.class));
    }

    @Override
    public String backtestHoldings(String jobId, String strategy, String date) {
        return call(() -> rest.get().uri(b -> {
            b.path("/internal/v1/backtests/{id}/holdings").queryParam("strategy", strategy);
            if (date != null && !date.isBlank()) {
                b.queryParam("date", date);
            }
            return b.build(jobId);
        }).retrieve().body(String.class));
    }

    @Override
    public String backtestCsv(String jobId) {
        return call(() -> rest.get().uri("/internal/v1/backtests/{id}/export.csv", jobId).retrieve().body(String.class));
    }

    /**
     * 엔진 오류를 서비스 오류로 바꾼다 (docs/02 §5).
     * <ul>
     *   <li>연결 실패·타임아웃 → QUANT_UNAVAILABLE</li>
     *   <li>엔진이 준 오류 코드가 우리 코드에도 있으면 그대로 (예: DATA_NOT_LOADED, INSUFFICIENT_UNIVERSE)</li>
     *   <li>그 밖(서비스 키 불일치 401, 엔진 500 등) → QUANT_UNAVAILABLE. 원인은 로그에만 남긴다</li>
     * </ul>
     */
    static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (ResourceAccessException e) {
            throw new BusinessException(ErrorCode.QUANT_UNAVAILABLE);
        } catch (RestClientResponseException e) {
            String engineCode = engineErrorCode(e.getResponseBodyAsString());
            ErrorCode mapped = passThrough(engineCode);
            if (mapped != null) {
                throw new BusinessException(mapped, Map.of("engineCode", engineCode));
            }
            log.warn("엔진 오류 status={} code={} body={}", e.getStatusCode().value(), engineCode,
                    e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.QUANT_UNAVAILABLE);
        }
    }

    private static String engineErrorCode(String body) {
        try {
            JsonNode code = JSON.readTree(body).path("error").path("code");
            return code.isString() ? code.asString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 사용자에게 그대로 전달해도 되는 엔진 코드 (docs/02 §9). */
    private static ErrorCode passThrough(String engineCode) {
        if (engineCode == null) {
            return null;
        }
        return switch (engineCode) {
            case "DATA_NOT_LOADED", "INSUFFICIENT_UNIVERSE", "INSUFFICIENT_HISTORY", "VALIDATION_ERROR",
                 "UNKNOWN_STOCK", "NOTHING_TO_REBALANCE", "BACKTEST_NOT_FOUND" -> ErrorCode.valueOf(engineCode);
            default -> null;
        };
    }
}
