package kr.team2.portfolio.portfolio.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.team2.portfolio.common.BusinessException;
import kr.team2.portfolio.common.ErrorCode;
import kr.team2.portfolio.portfolio.api.HoldingsChanged;
import kr.team2.portfolio.portfolio.api.HoldingsSnapshot;
import kr.team2.portfolio.portfolio.api.PortfolioFacade;
import kr.team2.portfolio.portfolio.api.PortfolioUpdate;
import kr.team2.portfolio.portfolio.api.PurchaseOrder;
import kr.team2.portfolio.portfolio.api.RebalanceOrder;
import kr.team2.portfolio.profile.api.OnboardingCompleted;
import kr.team2.portfolio.profile.api.ProfileFacade;
import kr.team2.portfolio.profile.api.ProfileSettings;
import kr.team2.portfolio.quantclient.api.ComputeRequest;
import kr.team2.portfolio.quantclient.api.EvaluateRequest;
import kr.team2.portfolio.quantclient.api.QuantClient;
import kr.team2.portfolio.quantclient.api.StockCatalog;
import kr.team2.portfolio.quantclient.api.StockInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 포트폴리오 보관·조회·평가·수정·매수 완료 (docs/03 §6.4, §7.3 #16~18). */
@Service
class PortfolioService implements PortfolioFacade {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String BOND = "BOND";
    private static final String CASH = "CASH";

    private final PortfolioRepository portfolios;
    private final QuantClient quant;
    private final StockCatalog catalog;
    private final ProfileFacade profiles;
    private final ApplicationEventPublisher events;
    private final JsonMapper json;
    private final Clock clock;

    @Autowired
    PortfolioService(PortfolioRepository portfolios, QuantClient quant, StockCatalog catalog, ProfileFacade profiles,
                     ApplicationEventPublisher events, JsonMapper json) {
        this(portfolios, quant, catalog, profiles, events, json, Clock.systemUTC());
    }

    PortfolioService(PortfolioRepository portfolios, QuantClient quant, StockCatalog catalog, ProfileFacade profiles,
                     ApplicationEventPublisher events, JsonMapper json, Clock clock) {
        this.portfolios = portfolios;
        this.quant = quant;
        this.catalog = catalog;
        this.profiles = profiles;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    /**
     * 온보딩 때 받은 기존 보유로 포트폴리오를 만든다 (docs/02 §3.3).
     * 처음부터 다시 하기면 있던 포트폴리오를 비우고(매수 기록 포함) 새 보유로 채운다.
     * 온보딩과 같은 트랜잭션에서 동기로 처리해, 실패하면 온보딩도 취소되게 한다.
     */
    @EventListener
    @Transactional
    void on(OnboardingCompleted event) {
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        PortfolioEntity pf = portfolios.findByUserId(event.userId())
                .map(old -> {
                    old.reset(today);
                    return portfolios.saveAndFlush(old);   // 지운 행을 먼저 내보내야 같은 종목 INSERT와 키가 겹치지 않는다
                })
                .orElseGet(() -> portfolios.saveAndFlush(
                        new PortfolioEntity(event.userId(), today, LocalDateTime.now(clock))));
        event.holdings().forEach(h -> pf.getHoldings().add(new HoldingEntity(
                pf.getId(), h.code(), h.quantity(), null, HoldingEntity.Origin.EXISTING)));
        portfolios.save(pf);
    }

    @Override
    @Transactional(readOnly = true)
    public HoldingsSnapshot holdingsSnapshot(long userId) {
        return portfolios.findByUserId(userId)
                .map(pf -> new HoldingsSnapshot(
                        pf.getHoldings().stream()
                                .map(h -> new HoldingsSnapshot.Holding(h.getCode(), h.getQuantity()))
                                .toList(),
                        pf.getBondUnits(), pf.getCash()))
                .orElseGet(HoldingsSnapshot::empty);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasPurchased(long userId) {
        return portfolios.findByUserId(userId).map(pf -> pf.getPurchasedAt() != null).orElse(false);
    }

    // ================================================================ 매수 완료

    @Override
    @Transactional
    public void purchase(long userId, PurchaseOrder order) {
        PortfolioEntity pf = find(userId);

        // 2. 수량 합산·평균가 갱신. 새 종목은 SERVICE
        Map<String, HoldingEntity> held = pf.getHoldings().stream()
                .collect(Collectors.toMap(HoldingEntity::getCode, Function.identity()));
        for (PurchaseOrder.Buy b : order.buys()) {
            if (b.shares() <= 0) {
                continue;
            }
            HoldingEntity h = held.get(b.code());
            if (h != null) {
                h.buy(b.shares(), b.price());
            } else {
                HoldingEntity added = new HoldingEntity(pf.getId(), b.code(), b.shares(), b.price(),
                        HoldingEntity.Origin.SERVICE);
                pf.getHoldings().add(added);
                held.put(b.code(), added);
            }
        }

        // 3·5. 채권·현금, 기준일
        pf.addPurchase(order.bondUnits(), order.cash(), order.asOf());

        // 4. 목표 비중 교체
        replaceTargets(pf, order.targetWeights());
        portfolios.save(pf);
    }

    /** 같은 키를 지웠다 다시 넣으면 INSERT가 DELETE보다 먼저 나가 키가 겹치므로, 있는 행은 고치고 없어진 행만 지운다. */
    private void replaceTargets(PortfolioEntity pf, Map<String, Double> targets) {
        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, Double> wanted = new HashMap<>(targets);
        pf.getTargetWeights().removeIf(t -> !wanted.containsKey(t.getAsset()));
        for (TargetWeightEntity t : pf.getTargetWeights()) {
            t.update(wanted.remove(t.getAsset()), now);
        }
        wanted.forEach((asset, w) -> pf.getTargetWeights().add(new TargetWeightEntity(pf.getId(), asset, w, now)));
    }

    // ================================================================ 알림 모듈용

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> portfolioId(long userId) {
        return portfolios.findByUserId(userId).map(PortfolioEntity::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public EvaluateRequest engineRequest(long userId) {
        PortfolioEntity pf = find(userId);
        ProfileSettings settings = profiles.currentSettings(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROFILE_NOT_FOUND));
        return request(pf, settings.toEngine(), targetsOf(pf));
    }

    @Override
    @Transactional
    public void markR6Opened(long userId, int year) {
        find(userId).setLastR6Year((short) year);
    }

    @Override
    @Transactional
    public void applyRebalance(long userId, RebalanceOrder order) {
        PortfolioEntity pf = find(userId);
        Map<String, HoldingEntity> held = pf.getHoldings().stream()
                .collect(Collectors.toMap(HoldingEntity::getCode, Function.identity()));
        pf.getHoldings().removeIf(h -> order.quantities().getOrDefault(h.getCode(), 0) <= 0);
        order.quantities().forEach((code, q) -> {
            if (q <= 0) {
                return;
            }
            HoldingEntity h = held.get(code);
            Double price = order.buyPrices().get(code);
            if (h == null) {
                pf.getHoldings().add(new HoldingEntity(pf.getId(), code, q, price, HoldingEntity.Origin.SERVICE));
            } else if (q > h.getQuantity() && price != null) {
                h.buy(q - h.getQuantity(), price);
            } else {
                h.setQuantity(q);      // 판 경우: 평균가는 그대로
            }
        });
        pf.setBondUnits(order.bondUnits());
        pf.setCash(order.cash());
        pf.setHoldingsChangedAt(order.asOf());
        replaceTargets(pf, order.targetWeights());
        portfolios.save(pf);
        events.publishEvent(new HoldingsChanged(userId));
    }

    private static Map<String, Double> targetsOf(PortfolioEntity pf) {
        return pf.getTargetWeights().stream()
                .collect(Collectors.toMap(TargetWeightEntity::getAsset, TargetWeightEntity::getWeight));
    }

    // ================================================================ 평가

    @Override
    @Transactional(readOnly = true)
    public ObjectNode evaluate(long userId) {
        PortfolioEntity pf = find(userId);
        ProfileSettings settings = profiles.currentSettings(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROFILE_NOT_FOUND));
        Map<String, Double> targets = targetsOf(pf);
        JsonNode r = json.readTree(quant.evaluatePortfolio(request(pf, settings.toEngine(), targets)));
        return view(pf, settings, targets, r);
    }

    private static EvaluateRequest request(PortfolioEntity pf, ComputeRequest.Settings settings,
                                           Map<String, Double> targets) {
        return new EvaluateRequest(settings,
                pf.getHoldings().stream().map(h -> new ComputeRequest.Holding(h.getCode(), h.getQuantity())).toList(),
                pf.getBondUnits(), pf.getCash(), targets, pf.getPurchasedAt(), pf.getHoldingsChangedAt(),
                pf.getLastR6Year() == null ? null : pf.getLastR6Year().intValue());
    }

    /** 엔진 평가 + 우리가 아는 것(이름·평균가·출처·목표 비중) → 화면용 JSON (docs/03 §7.3 #16). */
    private ObjectNode view(PortfolioEntity pf, ProfileSettings settings, Map<String, Double> targets, JsonNode r) {
        Map<String, HoldingEntity> held = pf.getHoldings().stream()
                .collect(Collectors.toMap(HoldingEntity::getCode, Function.identity()));
        ObjectNode out = json.createObjectNode();
        out.put("asOf", r.path("as_of").asString());
        out.put("dataVersion", r.path("data_version").asLong());
        out.put("totalValue", r.path("total_value").asLong());
        out.put("bondIndex", r.path("bond_index").asDouble());
        out.set("drawdown", r.path("drawdown"));
        out.put("purchased", pf.getPurchasedAt() != null);
        out.put("purchasedAt", pf.getPurchasedAt() == null ? null : pf.getPurchasedAt().toString());
        out.put("holdingsChangedAt", pf.getHoldingsChangedAt().toString());
        out.set("byAsset", r.path("by_asset"));

        ArrayNode stocks = out.putArray("stocks");
        double stockTarget = 0;
        for (JsonNode a : r.path("assets")) {
            String code = a.path("asset").asString();
            if (code.equals(BOND)) {
                ObjectNode b = out.putObject("bond");
                b.put("units", a.path("units").asDouble());
                b.put("value", a.path("value").asLong());
                b.put("weight", a.path("weight").asDouble());
                putTarget(b, targets.get(BOND));
            } else if (code.equals(CASH)) {
                ObjectNode c = out.putObject("cash");
                c.put("value", a.path("value").asLong());
                c.put("weight", a.path("weight").asDouble());
                putTarget(c, targets.get(CASH));
            } else {
                HoldingEntity h = held.get(code);
                ObjectNode s = stocks.addObject();
                s.put("code", code);
                s.put("name", catalog.find(code).map(StockInfo::name).orElse(code));
                s.put("sector", a.path("sector").asString());
                s.put("quantity", a.path("quantity").asInt());
                s.set("close", a.path("close"));
                s.put("value", a.path("value").asLong());
                s.put("weight", a.path("weight").asDouble());
                s.put("weightInStock", a.path("weight_in_stock").asDouble());
                putTarget(s, targets.get(code));
                Double avg = h == null ? null : h.getAvgPrice();
                s.put("avgPrice", avg);
                double close = a.path("close").asDouble(0);
                s.put("returnRate", avg == null || avg <= 0 || close <= 0 ? null : round4(close / avg - 1));
                s.put("origin", h == null ? null : h.getOrigin().name());
                s.set("delistRules", a.path("delist_rules"));
                stockTarget += targets.getOrDefault(code, 0.0);
            }
        }
        if (targets.isEmpty()) {
            out.putNull("targetByAsset");
        } else {
            out.putObject("targetByAsset")
                    .put("stock", round4(stockTarget))
                    .put("bond", targets.getOrDefault(BOND, 0.0))
                    .put("cash", targets.getOrDefault(CASH, 0.0));
        }
        // 목표 비중에는 있는데 지금은 없는 종목 (직접 판 경우)
        ArrayNode gone = out.putArray("targetOnly");
        targets.forEach((k, w) -> {
            if (!k.equals(BOND) && !k.equals(CASH) && !held.containsKey(k)) {
                gone.addObject().put("code", k).put("name", catalog.find(k).map(StockInfo::name).orElse(k))
                        .put("targetWeight", w);
            }
        });

        ArrayNode sectors = out.putArray("bySectorInStock");
        r.path("by_sector_in_stock").forEach(sectors::add);
        out.set("conditions", r.path("conditions"));
        ObjectNode limits = out.putObject("limits");
        limits.put("stockLimit", settings.stockLimit());
        limits.put("sectorLimit", settings.sectorLimit());
        limits.put("lossLimit", settings.lossLimit());
        return out;
    }

    private static void putTarget(ObjectNode node, Double target) {
        node.put("targetWeight", target);
    }

    private static double round4(double x) {
        return Math.round(x * 10_000) / 10_000.0;
    }

    // ================================================================ 직접 수정

    @Override
    @Transactional
    public void update(long userId, PortfolioUpdate update) {
        PortfolioEntity pf = find(userId);
        List<PortfolioUpdate.HoldingEdit> edits = update.holdings() == null ? List.of() : update.holdings();

        List<String> unknown = new ArrayList<>();
        for (PortfolioUpdate.HoldingEdit e : edits) {
            if (e.code() == null || e.quantity() < 0 || (e.avgPrice() != null && e.avgPrice() < 0)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        Map.of("field", "holdings", "code", String.valueOf(e.code())));
            }
            if (e.quantity() > 0 && catalog.find(e.code().trim()).isEmpty()) {
                unknown.add(e.code());
            }
        }
        if (!unknown.isEmpty()) {
            throw new BusinessException(ErrorCode.UNKNOWN_STOCK, Map.of("codes", unknown));
        }
        if ((update.cash() != null && update.cash() < 0) || (update.bondAmount() != null && update.bondAmount() < 0)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of("field", "cash/bondAmount"));
        }

        Map<String, HoldingEntity> held = pf.getHoldings().stream()
                .collect(Collectors.toMap(HoldingEntity::getCode, Function.identity()));
        for (PortfolioUpdate.HoldingEdit e : edits) {
            String code = e.code().trim();
            HoldingEntity h = held.get(code);
            Double avg = e.avgPrice() == null || e.avgPrice() == 0 ? null : e.avgPrice();
            if (e.quantity() == 0) {
                if (h != null) {
                    pf.getHoldings().remove(h);
                    held.remove(code);
                }
            } else if (h != null) {
                h.setQuantity(e.quantity());
                if (e.avgPrice() != null) {
                    h.setAvgPrice(avg);     // 0을 보내면 '모름'으로 되돌린다
                }
            } else {
                HoldingEntity added = new HoldingEntity(pf.getId(), code, e.quantity(), avg, HoldingEntity.Origin.EXISTING);
                pf.getHoldings().add(added);
                held.put(code, added);
            }
        }
        if (update.bondAmount() != null) {
            pf.setBondUnits(update.bondAmount() == 0 ? 0 : update.bondAmount() / bondIndex(userId, pf));
        }
        if (update.cash() != null) {
            pf.setCash(update.cash());
        }
        pf.setHoldingsChangedAt(catalog.asOf());   // R5는 이날부터 다시 잰다
        portfolios.save(pf);
        events.publishEvent(new HoldingsChanged(userId));
    }

    /** 채권 금액 → 단위 변환에 쓰는 최신 채권 지수 (엔진 I8 bond_index). */
    private double bondIndex(long userId, PortfolioEntity pf) {
        ProfileSettings settings = profiles.currentSettings(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROFILE_NOT_FOUND));
        EvaluateRequest req = new EvaluateRequest(settings.toEngine(), List.of(), 0, 0, Map.of(),
                null, null, null);
        return json.readTree(quant.evaluatePortfolio(req)).path("bond_index").asDouble();
    }

    private PortfolioEntity find(long userId) {
        return portfolios.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PORTFOLIO_NOT_FOUND));
    }
}
