package kr.team2.portfolio.alert.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.team2.portfolio.alert.api.AlertFacade;
import kr.team2.portfolio.alert.api.AlertType;
import kr.team2.portfolio.alert.api.AlertView;
import kr.team2.portfolio.common.BusinessException;
import kr.team2.portfolio.common.ErrorCode;
import kr.team2.portfolio.common.Texts;
import kr.team2.portfolio.portfolio.api.HoldingsSnapshot;
import kr.team2.portfolio.portfolio.api.PortfolioFacade;
import kr.team2.portfolio.portfolio.api.RebalanceOrder;
import kr.team2.portfolio.quantclient.api.QuantClient;
import kr.team2.portfolio.quantclient.api.RebalanceRequest;
import kr.team2.portfolio.quantclient.api.StockCatalog;
import kr.team2.portfolio.quantclient.api.StockInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 알림 상태 동기화·닫기·리밸런싱 제안·반영 (docs/03 §6.5). */
@Service
class AlertService implements AlertFacade {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final Set<AlertEntity.Status> ACTIVE = EnumSet.of(AlertEntity.Status.OPEN, AlertEntity.Status.DISMISSED);

    private final AlertRepository alerts;
    private final PortfolioFacade portfolios;
    private final QuantClient quant;
    private final StockCatalog catalog;
    private final JsonMapper json;
    private final AlertTexts texts;
    private final Clock clock;

    @Autowired
    AlertService(AlertRepository alerts, PortfolioFacade portfolios, QuantClient quant, StockCatalog catalog,
                 JsonMapper json) {
        this(alerts, portfolios, quant, catalog, json, Clock.systemUTC());
    }

    AlertService(AlertRepository alerts, PortfolioFacade portfolios, QuantClient quant, StockCatalog catalog,
                 JsonMapper json, Clock clock) {
        this.alerts = alerts;
        this.portfolios = portfolios;
        this.quant = quant;
        this.catalog = catalog;
        this.json = json;
        this.texts = new AlertTexts(Texts.load("messages_alerts.properties"),
                code -> catalog.find(code).map(StockInfo::name).orElse(code));
        this.clock = clock;
    }

    // ================================================================ 상태 맞추기

    @Override
    @Transactional
    public List<AlertView> sync(long userId, JsonNode conditions) {
        long pid = portfolioId(userId);
        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, AlertEntity> active = alerts.findByPortfolioIdAndStatusInOrderByOpenedAtAscIdAsc(pid, ACTIVE)
                .stream().collect(Collectors.toMap(AlertEntity::key, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Set<String> seen = new HashSet<>();

        for (JsonNode c : conditions) {
            AlertType type;
            try {
                type = AlertType.valueOf(c.path("type").asString(""));
            } catch (IllegalArgumentException e) {
                log.warn("모르는 알림 조건: {}", c);
                continue;
            }
            String target = c.path("target").asString("");
            String detail = json.writeValueAsString(c.path("detail"));
            AlertEntity a = active.get(type + ":" + target);
            seen.add(type + ":" + target);
            if (a != null) {
                a.updateDetail(detail);                   // 상태는 그대로 (닫은 알림은 닫힌 채로)
            } else {
                alerts.save(new AlertEntity(pid, type, target, detail, now));
                if (type == AlertType.R6) {
                    portfolios.markR6Opened(userId, c.path("detail").path("year").asInt());
                }
            }
        }
        // 조건이 사라진 알림은 닫는다. R6은 연 1회라 엔진이 다시 내지 않으므로 사용자가 닫을 때까지 둔다
        active.values().stream()
                .filter(a -> !seen.contains(a.key()) && a.getType() != AlertType.R6)
                .forEach(a -> a.close(now));
        alerts.flush();
        return list(userId, AlertEntity.Status.OPEN.name());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AlertView> list(long userId, String status) {
        AlertEntity.Status s = status == null ? AlertEntity.Status.OPEN : parseStatus(status);
        return alerts.findByPortfolioIdAndStatusInOrderByOpenedAtAscIdAsc(portfolioId(userId), Set.of(s))
                .stream().map(this::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AlertView get(long userId, long alertId) {
        return view(owned(userId, alertId));
    }

    @Override
    @Transactional
    public void dismiss(long userId, long alertId) {
        AlertEntity a = owned(userId, alertId);
        if (!a.isActive()) {
            throw new BusinessException(ErrorCode.ALERT_CLOSED);
        }
        if (a.getType() == AlertType.R6) {
            a.close(LocalDateTime.now(clock));     // 연 1회 알림은 닫으면 끝 (DISMISSED로 두면 다음 해 R6가 막힌다)
        } else {
            a.dismiss();
        }
    }

    // ================================================================ 리밸런싱 제안

    private JsonNode plan(long userId, AlertEntity a) {
        RebalanceRequest req = RebalanceRequest.of(portfolios.engineRequest(userId), a.getType().name(), a.getTarget());
        return json.readTree(quant.rebalancePlan(req));
    }

    @Override
    @Transactional(readOnly = true)
    public ObjectNode suggestion(long userId, long alertId) {
        AlertEntity a = owned(userId, alertId);
        JsonNode p = plan(userId, a);

        ObjectNode out = json.createObjectNode();
        out.set("alert", json.valueToTree(view(a)));
        out.put("help", texts.help(a.getType()));
        out.put("asOf", p.path("as_of").asString());
        out.put("totalValue", p.path("total_value").asLong());
        ArrayNode trades = out.putArray("trades");
        for (JsonNode t : p.path("trades")) {
            String asset = t.path("asset").asString();
            ObjectNode o = trades.addObject();
            o.put("asset", asset);
            o.put("name", texts.assetName(asset));
            o.put("side", t.path("side").asString());
            o.put("sideLabel", texts.side(t.path("side").asString()));
            if (t.has("quantity")) {
                o.put("quantity", t.path("quantity").asInt());
                o.put("close", t.path("close").asDouble());
            }
            o.put("amount", t.path("amount").asLong());
        }
        out.set("before", p.path("before").path("by_asset"));
        ObjectNode after = out.putObject("after");
        after.set("byAsset", p.path("after").path("by_asset"));
        after.put("cash", p.path("after").path("cash").asLong());
        ArrayNode weights = out.putArray("targets");
        JsonNode afterW = p.path("after").path("weights");
        p.path("target_weights").properties().forEach(e -> weights.addObject()
                .put("asset", e.getKey()).put("name", texts.assetName(e.getKey()))
                .put("target", e.getValue().asDouble()).put("after", afterW.path(e.getKey()).asDouble(0)));
        out.put("canApply", a.isActive());
        return out;
    }

    @Override
    @Transactional
    public void apply(long userId, long alertId, Map<String, Long> traded) {
        AlertEntity a = owned(userId, alertId);
        if (!a.isActive()) {
            throw new BusinessException(ErrorCode.ALERT_CLOSED);
        }
        JsonNode p = plan(userId, a);        // 그사이 가격·보유가 바뀌었을 수 있어 다시 계산한다
        Map<String, Integer> quantities = new LinkedHashMap<>();
        p.path("after").path("quantities").properties().forEach(e -> quantities.put(e.getKey(), e.getValue().asInt()));
        Map<String, Double> buyPrices = new LinkedHashMap<>();
        for (JsonNode t : p.path("trades")) {
            if ("BUY".equals(t.path("side").asString()) && t.has("close")) {
                buyPrices.put(t.path("asset").asString(), t.path("close").asDouble());
            }
        }
        Map<String, Double> targets = new LinkedHashMap<>();
        p.path("target_weights").properties().forEach(e -> targets.put(e.getKey(), e.getValue().asDouble()));
        LocalDate asOf = LocalDate.parse(p.path("as_of").asString());
        RebalanceOrder order = traded == null
                ? new RebalanceOrder(quantities, buyPrices, p.path("after").path("bond_units").asDouble(),
                        p.path("after").path("cash").asLong(), targets, asOf)
                : manualOrder(portfolios.holdingsSnapshot(userId), p, traded, targets, asOf);
        portfolios.applyRebalance(userId, order);
        a.close(LocalDateTime.now(clock));
    }

    /** 사용자가 직접 넣은 거래량으로 거래 후 수량·채권·현금을 계산한다. 방향과 가격은 제안을 따른다. */
    static RebalanceOrder manualOrder(HoldingsSnapshot now, JsonNode plan, Map<String, Long> traded,
                                      Map<String, Double> targets, LocalDate asOf) {
        Map<String, Integer> quantities = new LinkedHashMap<>();
        now.holdings().forEach(h -> quantities.put(h.code(), h.quantity()));
        Map<String, Double> buyPrices = new LinkedHashMap<>();
        double bondIndex = plan.path("bond_index").asDouble();
        double bondUnits = now.bondUnits();
        double cash = now.cash();
        for (JsonNode t : plan.path("trades")) {
            String asset = t.path("asset").asString();
            long n = traded.getOrDefault(asset, 0L);
            if (n < 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of("field", "traded", "asset", asset));
            }
            int sign = "BUY".equals(t.path("side").asString()) ? 1 : -1;
            if (asset.equals("BOND")) {
                bondUnits += sign * n / bondIndex;
                cash -= sign * n;
                if (bondUnits < -1e-6) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of("field", "traded", "asset", asset));
                }
                bondUnits = Math.max(0, bondUnits);
            } else {
                double close = t.path("close").asDouble();
                long after = quantities.getOrDefault(asset, 0) + sign * n;
                if (after < 0 || after > Integer.MAX_VALUE) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of("field", "traded", "asset", asset));
                }
                quantities.put(asset, (int) after);
                cash -= sign * n * close;
                if (sign > 0 && n > 0) {
                    buyPrices.put(asset, close);
                }
            }
        }
        if (cash < -0.5) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of("field", "cash", "reason", "NOT_ENOUGH"));
        }
        return new RebalanceOrder(quantities, buyPrices, bondUnits, Math.max(0, Math.round(cash)), targets, asOf);
    }

    // ================================================================ 도우미

    private AlertView view(AlertEntity a) {
        return new AlertView(a.getId(), a.getType(), a.getTarget(), texts.title(a.getType()),
                texts.text(a.getType(), a.getTarget(), json.readTree(a.getDetail())), a.getStatus().name(),
                a.getOpenedAt());
    }

    private long portfolioId(long userId) {
        return portfolios.portfolioId(userId).orElseThrow(() -> new BusinessException(ErrorCode.PORTFOLIO_NOT_FOUND));
    }

    private AlertEntity owned(long userId, long alertId) {
        return alerts.findByIdAndPortfolioId(alertId, portfolioId(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ALERT_NOT_FOUND));
    }

    private static AlertEntity.Status parseStatus(String s) {
        try {
            return AlertEntity.Status.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of("field", "status"));
        }
    }
}
