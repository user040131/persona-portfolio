package kr.team2.portfolio.quantclient.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import kr.team2.portfolio.common.BusinessException;
import kr.team2.portfolio.common.ErrorCode;
import kr.team2.portfolio.quantclient.api.BacktestRequest;
import kr.team2.portfolio.quantclient.api.ComputeRequest;
import kr.team2.portfolio.quantclient.api.EngineHealth;
import kr.team2.portfolio.quantclient.api.EvaluateRequest;
import kr.team2.portfolio.quantclient.api.QuantClient;
import kr.team2.portfolio.quantclient.api.RebalanceRequest;
import kr.team2.portfolio.quantclient.api.StockList;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 엔진 없이 개발할 때 쓰는 대역 ({@code quant.stub-enabled=true}).
 * <ul>
 *   <li>종목 목록: 제공 파일(2026-08-19 종가)로 만든 {@code quant-stub/stocks.json} (엔진 I4와 같은 형식)</li>
 *   <li>추천 계산: 실제 엔진이 기준 프로필(중립, 3,000만 원, 삼성전자 20주)로 낸 응답
 *       {@code quant-stub/compute.json}. 입력과 관계없이 늘 같은 결과를 돌려준다</li>
 *   <li>포트폴리오 평가·리밸런싱: 대역 종목 종가로 계산 ({@link StubEvaluator}).
 *       리밸런싱은 알림 종류와 관계없이 저장된 목표 비중으로만 계산한다</li>
 *   <li>백테스트·과거 성과 카드: 실제 엔진이 2024~2026 기간(실험 A~D, 중립형 대표 1개)으로 낸 결과
 *       {@code quant-stub/backtest.json}, {@code history-card.json}. 파라미터와 관계없이 같은 결과</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "quant", name = "stub-enabled", havingValue = "true")
class StubQuantClient implements QuantClient {

    private static final String STOCKS = "quant-stub/stocks.json";
    private static final String COMPUTE = "quant-stub/compute.json";

    private final StockList stocks;
    private final String compute;
    private final StubEvaluator evaluator;
    private final JsonNode backtest = JsonMapper.builder().build().readTree(read("quant-stub/backtest.json"));
    private final String historyCard = read("quant-stub/history-card.json");

    StubQuantClient() {
        try (InputStream in = new ClassPathResource(STOCKS).getInputStream()) {
            this.stocks = JsonMapper.builder().build()
                    .readValue(in, EngineDtos.StocksResponse.class)
                    .toApi();
        } catch (IOException e) {
            throw new UncheckedIOException("대역 종목 목록을 읽을 수 없어요: " + STOCKS, e);
        }
        try (InputStream in = new ClassPathResource(COMPUTE).getInputStream()) {
            this.compute = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("대역 추천 결과를 읽을 수 없어요: " + COMPUTE, e);
        }
        this.evaluator = new StubEvaluator(stocks);
    }

    @Override
    public EngineHealth health() {
        return new EngineHealth("stub", true);
    }

    @Override
    public StockList stocks() {
        return stocks;
    }

    @Override
    public String computeRecommendation(ComputeRequest request) {
        return compute;
    }

    @Override
    public String evaluatePortfolio(EvaluateRequest request) {
        return evaluator.evaluate(request);
    }

    @Override
    public String rebalancePlan(RebalanceRequest request) {
        return evaluator.rebalance(request);
    }

    @Override
    public String historyCard(ComputeRequest.Settings settings, int startYear) {
        return historyCard;
    }

    @Override
    public String createBacktest(BacktestRequest request) {
        return UUID.randomUUID().toString();      // backtest_job.job_id(CHAR 36)가 유일해야 해서 매번 새 ID
    }

    @Override
    public String backtestStatus(String jobId) {
        return ((tools.jackson.databind.node.ObjectNode) stubJob(jobId).path("status").deepCopy())
                .put("job_id", jobId).toString();
    }

    @Override
    public String backtestNav(String jobId, String strategies, String freq) {
        // shortcut: 전략 선택은 무시하고 모두 준다, 대역에서 전략 거르기가 필요해지면 추가
        return stubJob(jobId).path("nav").path(freq == null ? "D" : freq).toString();
    }

    @Override
    public String backtestHoldings(String jobId, String strategy, String date) {
        JsonNode h = stubJob(jobId).path("holdings").path(strategy);
        return h.isMissingNode() ? "{\"strategy\":\"" + strategy + "\",\"dates\":[],\"date\":null,\"holdings\":[]}"
                : h.toString();
    }

    @Override
    public String backtestCsv(String jobId) {
        return stubJob(jobId).path("csv").asString();
    }

    private JsonNode stubJob(String jobId) {
        try {
            UUID.fromString(jobId);              // 대역은 모든 작업이 같은 결과. 작업 소유 확인은 Spring이 한다
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ErrorCode.BACKTEST_NOT_FOUND);
        }
        return backtest;
    }

    private static String read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("대역 파일을 읽을 수 없어요: " + path, e);
        }
    }
}
