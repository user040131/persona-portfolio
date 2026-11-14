package kr.team2.portfolio.backtest.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.team2.portfolio.backtest.api.BacktestFacade;
import kr.team2.portfolio.backtest.api.BacktestStatus;
import kr.team2.portfolio.common.BusinessException;
import kr.team2.portfolio.common.ErrorCode;
import kr.team2.portfolio.profile.api.Answers;
import kr.team2.portfolio.profile.api.ProfileFacade;
import kr.team2.portfolio.quantclient.api.BacktestRequest;
import kr.team2.portfolio.quantclient.api.QuantClient;
import kr.team2.portfolio.quantclient.api.StockCatalog;
import kr.team2.portfolio.quantclient.api.StockInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** 백테스트 작업 만들기·소유 확인·결과 전달 (docs/03 §6.6). */
@Service
class BacktestService implements BacktestFacade {

    static final List<String> ALL_EXPERIMENTS = List.of("A", "B", "C", "D");

    /** backtest-profiles.yml 한 줄 (docs/03 §5.3). */
    record ProfileDef(String key, List<Integer> q, int a2, boolean a3, int a4, int a5, List<String> a6,
                      List<String> a7) {
        Answers answers() {
            return new Answers(q, a2, a3, a4, a5, a6, a7);
        }
    }

    private final BacktestJobRepository jobs;
    private final QuantClient quant;
    private final ProfileFacade profiles;
    private final StockCatalog catalog;
    private static final Map<String, String> ASSET_NAMES = Map.of("BOND", "채권", "CASH", "현금", "KOSPI", "코스피");
    private final JsonMapper json;
    private final List<ProfileDef> profileDefs;
    private final Map<String, Double> multiWeights;
    private final Clock clock;

    @Autowired
    BacktestService(BacktestJobRepository jobs, QuantClient quant, ProfileFacade profiles, StockCatalog catalog,
                    JsonMapper json, Environment env) {
        this(jobs, quant, profiles, catalog, json, env, Clock.systemUTC());
    }

    BacktestService(BacktestJobRepository jobs, QuantClient quant, ProfileFacade profiles, StockCatalog catalog,
                    JsonMapper json, Environment env, Clock clock) {
        this.jobs = jobs;
        this.quant = quant;
        this.profiles = profiles;
        this.catalog = catalog;
        this.json = json;
        Binder binder = Binder.get(env);
        this.profileDefs = binder.bind("backtest.profiles", Bindable.listOf(ProfileDef.class)).orElse(List.of());
        // 실험 A~C의 멀티팩터 가중치 = 중립형 기본 가중치 (docs/03 §6.6)
        this.multiWeights = new java.util.TreeMap<>();
        binder.bind("portfolio.profile.factor-weights.neutral", Bindable.mapOf(String.class, Double.class)).get()
                .forEach((k, v) -> multiWeights.put(k.toUpperCase(), v));
        this.clock = clock;
    }

    @Override
    @Transactional
    public JobView create(long userId, CreateCommand cmd) {
        int startYear = cmd.startYear() == null ? 2015 : cmd.startYear();
        double cost = cmd.tradingCostRate() == null ? 0.003 : cmd.tradingCostRate();
        List<String> experiments = cmd.experiments() == null || cmd.experiments().isEmpty()
                ? ALL_EXPERIMENTS
                : cmd.experiments().stream().map(String::toUpperCase).distinct().sorted().toList();
        if (!ALL_EXPERIMENTS.containsAll(experiments)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of("field", "experiments"));
        }
        List<BacktestRequest.Profile> reps = experiments.contains("D")
                ? profileDefs.stream()
                        .map(p -> new BacktestRequest.Profile(p.key(), profiles.calculate(p.answers()).toEngine()))
                        .toList()
                : List.of();
        String jobId = quant.createBacktest(new BacktestRequest(startYear, cmd.endDate(), cost, experiments,
                multiWeights, reps));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("startYear", startYear);
        params.put("endDate", cmd.endDate() == null ? null : cmd.endDate().toString());
        params.put("tradingCostRate", cost);
        params.put("experiments", experiments);
        BacktestJobEntity saved = jobs.save(new BacktestJobEntity(userId, jobId, json.writeValueAsString(params),
                LocalDateTime.now(clock)));
        return view(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<JobView> list(long userId) {
        return jobs.findByUserIdOrderByCreatedAtDescIdDesc(userId).stream().map(this::view).toList();
    }

    @Override
    @Transactional
    public String status(long userId, String jobId) {
        BacktestJobEntity job = owned(userId, jobId);
        String raw = quant.backtestStatus(jobId);
        job.setLastStatus(BacktestStatus.valueOf(json.readTree(raw).path("status").asString()));
        return raw;
    }

    @Override
    @Transactional(readOnly = true)
    public String nav(long userId, String jobId, String strategies, String freq) {
        return quant.backtestNav(owned(userId, jobId).getJobId(), strategies, freq);
    }

    @Override
    @Transactional(readOnly = true)
    public String holdings(long userId, String jobId, String strategy, String date) {
        var res = (tools.jackson.databind.node.ObjectNode) json.readTree(
                quant.backtestHoldings(owned(userId, jobId).getJobId(), strategy, date));
        res.path("holdings").forEach(h -> ((tools.jackson.databind.node.ObjectNode) h).put("name",
                ASSET_NAMES.getOrDefault(h.path("asset").asString(), catalog.find(h.path("asset").asString())
                        .map(StockInfo::name).orElse(h.path("asset").asString()))));
        return res.toString();
    }

    @Override
    @Transactional(readOnly = true)
    public String csv(long userId, String jobId) {
        return quant.backtestCsv(owned(userId, jobId).getJobId());
    }

    private BacktestJobEntity owned(long userId, String jobId) {
        return jobs.findByJobIdAndUserId(jobId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BACKTEST_NOT_FOUND));
    }

    private JobView view(BacktestJobEntity e) {
        var p = json.readTree(e.getParams());
        String end = p.path("endDate").asString(null);
        return new JobView(e.getJobId(), e.getLastStatus(), p.path("startYear").asInt(),
                end == null ? null : LocalDate.parse(end), p.path("tradingCostRate").asDouble(),
                p.path("experiments").valueStream().map(n -> n.asString()).toList(), e.getCreatedAt());
    }
}
