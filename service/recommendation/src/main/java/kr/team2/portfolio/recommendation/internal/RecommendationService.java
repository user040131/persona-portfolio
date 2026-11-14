package kr.team2.portfolio.recommendation.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.team2.portfolio.common.BusinessException;
import kr.team2.portfolio.common.ErrorCode;
import kr.team2.portfolio.portfolio.api.HoldingsSnapshot;
import kr.team2.portfolio.portfolio.api.PortfolioFacade;
import kr.team2.portfolio.portfolio.api.PurchaseOrder;
import kr.team2.portfolio.profile.api.Answers;
import kr.team2.portfolio.profile.api.Factor;
import kr.team2.portfolio.profile.api.Method;
import kr.team2.portfolio.profile.api.ProfileFacade;
import kr.team2.portfolio.profile.api.ProfileSettings;
import kr.team2.portfolio.profile.api.ProfileView;
import kr.team2.portfolio.profile.api.Survey;
import kr.team2.portfolio.quantclient.api.ComputeRequest;
import kr.team2.portfolio.quantclient.api.QuantClient;
import kr.team2.portfolio.quantclient.api.StockCatalog;
import kr.team2.portfolio.quantclient.api.StockInfo;
import kr.team2.portfolio.recommendation.api.EditCommand;
import kr.team2.portfolio.recommendation.api.PurchaseCompleted;
import kr.team2.portfolio.recommendation.api.RecommendationFacade;
import kr.team2.portfolio.recommendation.api.WhyNot;
import kr.team2.portfolio.recommendation.internal.render.Renderer;
import kr.team2.portfolio.common.Texts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 추천 생성·수정·조회·왜 없나요·매수 완료 (docs/03 §6.2·6.4).
 * 엔진 호출(1~2초)은 DB 트랜잭션 밖에서 하고, 저장만 짧은 트랜잭션으로 묶는다.
 */
@Service
class RecommendationService implements RecommendationFacade {

    private static final String A2 = "a2";
    private static final String A5 = "a5";
    private static final int A2_NONE = 1;
    private static final int A5_ANY = 4;
    private static final int HISTORY_START_YEAR = 2015;

    private final RecommendationRepository recs;
    private final RecommendationVersionRepository versions;
    private final QuantClient quant;
    private final StockCatalog catalog;
    private final ProfileFacade profiles;
    private final PortfolioFacade portfolios;
    private final ApplicationEventPublisher events;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final Renderer renderer = new Renderer(Texts.load("messages_reasons.properties"));
    private final Clock clock;

    @Autowired
    RecommendationService(RecommendationRepository recs, RecommendationVersionRepository versions,
                          QuantClient quant, StockCatalog catalog, ProfileFacade profiles,
                          PortfolioFacade portfolios, ApplicationEventPublisher events, JsonMapper json,
                          TransactionTemplate tx) {
        this(recs, versions, quant, catalog, profiles, portfolios, events, json, tx, Clock.systemUTC());
    }

    RecommendationService(RecommendationRepository recs, RecommendationVersionRepository versions,
                          QuantClient quant, StockCatalog catalog, ProfileFacade profiles,
                          PortfolioFacade portfolios, ApplicationEventPublisher events, JsonMapper json,
                          TransactionTemplate tx, Clock clock) {
        this.recs = recs;
        this.versions = versions;
        this.quant = quant;
        this.catalog = catalog;
        this.profiles = profiles;
        this.portfolios = portfolios;
        this.events = events;
        this.json = json;
        this.tx = tx;
        this.clock = clock;
    }

    // ================================================================ 생성·수정

    @Override
    public ObjectNode create(long userId, Long investAmount) {
        ProfileView profile = profiles.current(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROFILE_NOT_FOUND));
        long amount = resolveAmount(userId, investAmount);
        String raw = compute(profile.settings(), amount, Edits.NONE, portfolios.holdingsSnapshot(userId));
        JsonNode result = json.readTree(raw);
        LocalDateTime now = LocalDateTime.now(clock);

        RecommendationEntity rec = tx.execute(s -> {
            RecommendationEntity saved = recs.saveAndFlush(new RecommendationEntity(userId, profile.profileId(),
                    result.path("data_version").asLong(), asOf(result), amount, now));
            versions.save(new RecommendationVersionEntity(saved.getId(), 1, write(Edits.NONE), raw, now));
            return saved;
        });
        return view(rec, 1, Edits.NONE, result, profile);
    }

    @Override
    public ObjectNode edit(long userId, long recommendationId, EditCommand command) {
        RecommendationEntity rec = owned(userId, recommendationId);
        if (rec.isPurchased()) {
            throw new BusinessException(ErrorCode.ALREADY_PURCHASED);
        }
        RecommendationVersionEntity latest = version(rec, rec.getLatestVersion());
        Edits merged = readEdits(latest).merge(command);
        List<String> unknown = merged.removedCodes().stream().filter(c -> catalog.find(c).isEmpty()).toList();
        if (!unknown.isEmpty()) {
            throw new BusinessException(ErrorCode.UNKNOWN_STOCK, Map.of("codes", unknown));
        }
        ProfileView profile = profileOf(rec);
        String raw = compute(profile.settings(), rec.getInvestAmount(), merged, portfolios.holdingsSnapshot(userId));
        JsonNode result = json.readTree(raw);
        int next = rec.getLatestVersion() + 1;

        RecommendationEntity saved = tx.execute(s -> {
            RecommendationEntity r = owned(userId, recommendationId);
            if (r.getLatestVersion() + 1 != next || r.isPurchased()) {
                throw new BusinessException(ErrorCode.STALE_RECOMMENDATION);   // 그사이 다른 수정·매수가 있었음
            }
            r.advance(result.path("data_version").asLong(), asOf(result));
            versions.save(new RecommendationVersionEntity(r.getId(), next, write(merged), raw,
                    LocalDateTime.now(clock)));
            return recs.save(r);
        });
        return view(saved, next, merged, result, profile);
    }

    private long resolveAmount(long userId, Long investAmount) {
        if (investAmount == null) {
            return profiles.pendingInvestAmount(userId).orElseThrow(() -> new BusinessException(
                    ErrorCode.VALIDATION_ERROR, Map.of("field", "investAmount", "reason", "REQUIRED")));
        }
        if (investAmount < profiles.minInvestAmount()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    Map.of("field", "investAmount", "min", profiles.minInvestAmount()));
        }
        return investAmount;
    }

    private String compute(ProfileSettings s, long amount, Edits edits, HoldingsSnapshot existing) {
        ComputeRequest req = new ComputeRequest(
                s.toEngine(),
                amount,
                new ComputeRequest.Existing(
                        existing.holdings().stream()
                                .map(h -> new ComputeRequest.Holding(h.code(), h.quantity())).toList(),
                        existing.bondUnits(), existing.cash()),
                new ComputeRequest.Edits(edits.removedCodes(), edits.safeRatio(),
                        edits.method() == null ? null : edits.method().name()));
        return quant.computeRecommendation(req);
    }

    // ================================================================ 조회

    @Override
    @Transactional(readOnly = true)
    public ObjectNode get(long userId, long recommendationId, Integer version) {
        RecommendationEntity rec = owned(userId, recommendationId);
        int v = version == null ? rec.getLatestVersion() : version;
        RecommendationVersionEntity ver = version(rec, v);
        return view(rec, v, readEdits(ver), json.readTree(ver.getResult()), profileOf(rec));
    }

    @Override
    @Transactional(readOnly = true)
    public String historyCard(long userId, long recommendationId) {
        return quant.historyCard(profileOf(owned(userId, recommendationId)).settings().toEngine(), HISTORY_START_YEAR);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> latestId(long userId) {
        return recs.findFirstByUserIdOrderByCreatedAtDescIdDesc(userId).map(RecommendationEntity::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public WhyNot whyNot(long userId, long recommendationId, String code) {
        RecommendationEntity rec = owned(userId, recommendationId);
        JsonNode result = json.readTree(version(rec, rec.getLatestVersion()).getResult());
        String c = code == null ? "" : code.trim();
        Optional<StockInfo> info = catalog.find(c);
        JsonNode entry = result.path("screening").path(c);
        if (entry.isMissingNode()) {
            StockInfo s = info.orElseThrow(() -> new BusinessException(ErrorCode.STOCK_NOT_FOUND));
            return new WhyNot(c, s.name(), false, "NO_PRICE",
                    renderer.whyNot("NO_PRICE", entry, profileOf(rec).gradeLabel()));
        }
        boolean picked = "PICKED".equals(entry.path("stage").asString(""));
        String reason = picked ? null : entry.path("reason").asString(null);
        String name = info.map(StockInfo::name).orElse(c);
        return new WhyNot(c, name, picked, reason,
                renderer.whyNot(reason, entry.path("params"), profileOf(rec).gradeLabel()));
    }

    // ================================================================ 매수 완료

    @Override
    @Transactional
    public void purchase(long userId, long recommendationId, int version) {
        RecommendationEntity rec = owned(userId, recommendationId);
        if (rec.isPurchased()) {
            throw new BusinessException(ErrorCode.ALREADY_PURCHASED);
        }
        long newest = latestId(userId).orElseThrow();
        if (version != rec.getLatestVersion() || newest != rec.getId()) {
            throw new BusinessException(ErrorCode.STALE_RECOMMENDATION,
                    Map.of("latestVersion", rec.getLatestVersion(), "latestRecommendationId", newest));
        }
        JsonNode result = json.readTree(version(rec, version).getResult());
        portfolios.purchase(userId, order(result));
        rec.markPurchased(version);
        recs.save(rec);
        profiles.clearPendingInvestAmount(userId);
        events.publishEvent(new PurchaseCompleted(userId, rec.getId(), version));
    }

    /** docs/03 §6.4 2~5단계에 쓸 값을 결과에서 꺼낸다. */
    static PurchaseOrder order(JsonNode result) {
        String method = result.path("method_selected").asString();
        List<PurchaseOrder.Buy> buys = new ArrayList<>();
        for (JsonNode item : result.path("methods").path(method).path("items")) {
            int shares = item.path("shares").asInt();
            if (shares > 0) {
                buys.add(new PurchaseOrder.Buy(item.path("code").asString(), shares, item.path("close").asDouble()));
            }
        }
        JsonNode a = result.path("allocation");
        Map<String, Double> targets = new LinkedHashMap<>();
        result.path("combined").path("target_weights").properties()
                .forEach(e -> targets.put(e.getKey(), e.getValue().asDouble()));
        return new PurchaseOrder(buys, a.path("bond_units").asDouble(), a.path("cash_amount").asLong(),
                targets, LocalDate.parse(result.path("as_of").asString()));
    }

    // ================================================================ 화면용 JSON

    /** I6 응답 → camelCase + 문구 (docs/03 §7.3 #10~12). screening은 너무 커서 뺀다. */
    private ObjectNode view(RecommendationEntity rec, int version, Edits edits, JsonNode result, ProfileView profile) {
        ObjectNode out = json.createObjectNode();
        boolean latest = version == rec.getLatestVersion();
        boolean newest = latestId(rec.getUserId()).map(id -> id.equals(rec.getId())).orElse(false);
        out.put("recommendationId", rec.getId());
        out.put("version", version);
        out.put("latestVersion", rec.getLatestVersion());
        out.put("purchased", rec.isPurchased());
        if (rec.getPurchasedVersion() != null) {
            out.put("purchasedVersion", rec.getPurchasedVersion());
        }
        out.put("canPurchase", latest && newest && !rec.isPurchased());
        out.put("investAmount", rec.getInvestAmount());
        out.put("createdAt", rec.getCreatedAt().toString());

        ObjectNode trimmed = json.createObjectNode();
        result.properties().stream()
                .filter(e -> !e.getKey().equals("screening"))
                .forEach(e -> trimmed.set(e.getKey(), e.getValue()));
        out.setAll((ObjectNode) CamelCase.convert(trimmed));
        out.set("edits", json.valueToTree(edits));

        ObjectNode p = out.putObject("profile");
        p.put("profileId", profile.profileId());
        p.put("version", profile.version());
        p.put("grade", profile.grade().name());
        p.put("gradeLabel", profile.gradeLabel());
        p.put("minCash", profile.settings().minCash());
        if (profile.settings().lossLimit() != null) {
            p.put("lossLimit", profile.settings().lossLimit());
        } else {
            p.putNull("lossLimit");
        }
        p.put("defaultMethod", profile.settings().defaultMethod().name());

        // 종목 근거
        ArrayNode stocks = (ArrayNode) out.path("stocks");
        for (int i = 0; i < stocks.size(); i++) {
            ArrayNode reasons = ((ObjectNode) stocks.get(i)).putArray("reasons");
            for (Renderer.Reason r : renderer.stockReasons(result.path("stocks").get(i), profile.gradeLabel())) {
                reasons.addObject().put("type", r.type()).put("text", r.text());
            }
        }

        // 포트폴리오 근거
        ArrayNode alloc = out.putArray("allocationReasons");
        renderer.allocationReasons(result, context(profile, edits)).forEach(alloc::add);

        // 경고 문구
        ArrayNode warnings = (ArrayNode) out.path("warnings");
        for (int i = 0; i < warnings.size(); i++) {
            String text = renderer.warning(result.path("warnings").get(i));
            ((ObjectNode) warnings.get(i)).put("text", text);
        }

        // 화면에 나오는 종목 이름: 추천 종목 + 합친 목표 비중(기존 보유) + 직접 뺀 종목
        ObjectNode names = out.putObject("names");
        result.path("stocks").forEach(s -> names.put(s.path("code").asString(), s.path("name").asString()));
        List<String> others = new ArrayList<>(edits.removedCodes());
        result.path("combined").path("target_weights").properties().forEach(e -> others.add(e.getKey()));
        for (String code : others) {
            if (!names.has(code) && !code.equals("BOND") && !code.equals("CASH")) {
                names.put(code, catalog.find(code).map(StockInfo::name).orElse(code));
            }
        }

        ObjectNode labels = out.putObject("labels");
        labels.set("methods", json.valueToTree(renderer.labels("method",
                List.of(Method.EQUAL.name(), Method.INVERSE_VOL.name(), Method.MIN_VARIANCE.name()))));
        labels.set("factors", json.valueToTree(renderer.labels("factor",
                java.util.Arrays.stream(Factor.values()).map(Enum::name).toList())));
        labels.set("assets", json.valueToTree(renderer.labels("asset", List.of("stock", "bond", "cash"))));
        return out;
    }

    private Renderer.ProfileContext context(ProfileView profile, Edits edits) {
        Survey survey = profiles.survey();
        Answers a = profile.answers();
        return new Renderer.ProfileContext(profile.gradeLabel(),
                optionText(survey, A2, a.a2()), a.a2() == A2_NONE, a.a3(),
                optionText(survey, A5, a.a5()), a.a5() == A5_ANY,
                edits.method() != null);
    }

    private static String optionText(Survey survey, String id, int choice) {
        return survey.extras().stream()
                .filter(q -> q.id().equals(id))
                .findFirst()
                .map(q -> q.options().get(choice - 1))
                .orElse("");
    }

    // ================================================================ 도우미

    private RecommendationEntity owned(long userId, long id) {
        return recs.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECOMMENDATION_NOT_FOUND));
    }

    private RecommendationVersionEntity version(RecommendationEntity rec, int v) {
        return versions.findById(new RecommendationVersionEntity.Key(rec.getId(), v))
                .orElseThrow(() -> new BusinessException(ErrorCode.RECOMMENDATION_NOT_FOUND,
                        Map.of("version", v)));
    }

    private ProfileView profileOf(RecommendationEntity rec) {
        return profiles.find(rec.getUserId(), rec.getProfileId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PROFILE_NOT_FOUND));
    }

    private Edits readEdits(RecommendationVersionEntity v) {
        return json.readValue(v.getEdits(), Edits.class);
    }

    private String write(Edits edits) {
        return json.writeValueAsString(edits);
    }

    private static LocalDate asOf(JsonNode result) {
        return LocalDate.parse(result.path("as_of").asString());
    }
}
