package kr.team2.portfolio.quantclient.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import kr.team2.portfolio.common.BusinessException;
import kr.team2.portfolio.common.ErrorCode;
import kr.team2.portfolio.quantclient.api.ComputeRequest;
import kr.team2.portfolio.quantclient.api.EvaluateRequest;
import kr.team2.portfolio.quantclient.api.RebalanceRequest;
import kr.team2.portfolio.quantclient.api.StockInfo;
import kr.team2.portfolio.quantclient.api.StockList;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 엔진 없이 개발할 때의 I8 대역. 대역 종목 목록의 종가와 기준 추천 결과의 채권 지수로 평가한다.
 * 과거 시세가 없으므로 알림 조건 중 R5(고점 대비 하락)·R6(정기 점검)은 보지 않는다.
 * 규칙은 엔진 {@code app/domain/alerts.py}와 같다.
 */
final class StubEvaluator {

    static final double BOND_INDEX = 152.214717;   // quant-stub/compute.json의 bond_index (2026-08-19)
    private static final double BAND = 0.05;

    private final StockList stocks;
    private final Map<String, StockInfo> byCode = new LinkedHashMap<>();
    private final JsonMapper json = JsonMapper.builder().build();

    StubEvaluator(StockList stocks) {
        this.stocks = stocks;
        stocks.items().forEach(s -> byCode.put(s.code(), s));
    }

    String evaluate(EvaluateRequest req) {
        Map<String, Integer> qty = new TreeMap<>();
        for (ComputeRequest.Holding h : req.holdings()) {
            qty.merge(h.code(), h.quantity(), Integer::sum);
        }
        List<String> unknown = qty.keySet().stream().filter(c -> !byCode.containsKey(c)).toList();
        if (!unknown.isEmpty()) {
            throw new BusinessException(ErrorCode.UNKNOWN_STOCK, Map.of("codes", unknown));
        }

        Map<String, Double> value = new LinkedHashMap<>();
        qty.forEach((c, q) -> value.put(c, q * close(c)));
        double stockTotal = value.values().stream().mapToDouble(Double::doubleValue).sum();
        double bond = req.bondUnits() * BOND_INDEX;
        double total = stockTotal + bond + req.cash();
        Map<String, Double> cur = new LinkedHashMap<>();
        value.forEach((c, v) -> cur.put(c, ratio(v, total)));
        cur.put("BOND", ratio(bond, total));
        cur.put("CASH", ratio(req.cash(), total));
        Map<String, Double> sectors = new TreeMap<>();
        value.forEach((c, v) -> sectors.merge(byCode.get(c).sector(), ratio(v, stockTotal), Double::sum));

        ObjectNode out = json.createObjectNode();
        out.put("as_of", stocks.asOf().toString());
        out.put("data_version", stocks.dataVersion());
        out.put("total_value", Math.round(total));
        out.put("bond_index", BOND_INDEX);
        ArrayNode assets = out.putArray("assets");
        value.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, Double> e) -> -e.getValue()).thenComparing(Map.Entry::getKey))
                .forEach(e -> {
                    StockInfo s = byCode.get(e.getKey());
                    ObjectNode a = assets.addObject();
                    a.put("asset", e.getKey());
                    a.put("quantity", qty.get(e.getKey()));
                    a.put("close", close(e.getKey()));
                    a.put("value", Math.round(e.getValue()));
                    a.put("weight", r(cur.get(e.getKey())));
                    a.put("weight_in_stock", r(ratio(e.getValue(), stockTotal)));
                    a.put("sector", s.sector());
                    a.set("delist_rules", json.valueToTree(s.delistRules()));
                });
        assets.addObject().put("asset", "BOND").put("units", req.bondUnits())
                .put("value", Math.round(bond)).put("weight", r(cur.get("BOND")));
        assets.addObject().put("asset", "CASH").put("value", req.cash()).put("weight", r(cur.get("CASH")));
        out.putObject("by_asset").put("stock", r(ratio(stockTotal, total)))
                .put("bond", r(cur.get("BOND"))).put("cash", r(cur.get("CASH")));
        ArrayNode bySector = out.putArray("by_sector_in_stock");
        sectors.entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .forEach(e -> bySector.addObject().put("sector", e.getKey()).put("weight", r(e.getValue())));
        out.putNull("drawdown");

        ArrayNode conditions = out.putArray("conditions");
        ComputeRequest.Settings st = req.settings();
        value.forEach((c, v) -> {
            double w = ratio(v, stockTotal);
            if (w > st.stockLimit()) {
                condition(conditions, "R1", c).put("weight", r(w)).put("limit", st.stockLimit());
            }
        });
        sectors.forEach((s, w) -> {
            if (w > st.sectorLimit()) {
                condition(conditions, "R2", s).put("weight", r(w)).put("limit", st.sectorLimit());
            }
        });
        Map<String, Double> targets = req.targets() == null ? Map.of() : req.targets();
        if (!targets.isEmpty()) {
            TreeSet<String> keys = new TreeSet<>(targets.keySet());
            keys.addAll(cur.keySet());
            for (String a : keys) {
                double now = cur.getOrDefault(a, 0.0);
                double target = targets.getOrDefault(a, 0.0);
                if (Math.abs(now - target) > BAND) {
                    condition(conditions, "R3", a).put("current", r(now)).put("target", r(target));
                }
            }
            double safeNow = cur.get("BOND") + cur.get("CASH");
            double safeTarget = targets.getOrDefault("BOND", 0.0) + targets.getOrDefault("CASH", 0.0);
            if (safeNow < safeTarget - BAND) {
                condition(conditions, "R4", "").put("current", r(safeNow)).put("target", r(safeTarget));
            }
        }
        qty.keySet().forEach(c -> {
            List<String> rules = byCode.get(c).delistRules();
            if (!rules.isEmpty()) {
                condition(conditions, "R7", c).set("rules", json.valueToTree(new ArrayList<>(rules)));
            }
        });
        return json.writeValueAsString(out);
    }

    /**
     * I9 대역: 알림 종류와 관계없이 저장된 목표 비중으로 1주 단위 내림 수량을 낸다 (엔진 rebalance.plan과 같은 식).
     */
    String rebalance(RebalanceRequest req) {
        Map<String, Integer> qty = new TreeMap<>();
        req.holdings().forEach(h -> qty.merge(h.code(), h.quantity(), Integer::sum));
        List<String> unknown = qty.keySet().stream().filter(c -> !byCode.containsKey(c)).toList();
        if (!unknown.isEmpty()) {
            throw new BusinessException(ErrorCode.UNKNOWN_STOCK, Map.of("codes", unknown));
        }
        double total = qty.entrySet().stream().mapToDouble(e -> e.getValue() * close(e.getKey())).sum()
                + req.bondUnits() * BOND_INDEX + req.cash();
        if (total <= 0) {
            throw new BusinessException(ErrorCode.NOTHING_TO_REBALANCE);
        }
        Map<String, Double> target = req.targets() == null ? Map.of() : req.targets();
        TreeSet<String> codes = new TreeSet<>(qty.keySet());
        target.keySet().stream().filter(k -> !k.equals("BOND") && !k.equals("CASH")).forEach(codes::add);

        ObjectNode out = json.createObjectNode();
        out.put("total_value", Math.round(total));
        ObjectNode tw = out.putObject("target_weights");
        target.forEach(tw::put);
        ArrayNode trades = out.putArray("trades");
        ObjectNode after = out.putObject("after");
        ObjectNode afterQty = after.putObject("quantities");
        ObjectNode weights = after.putObject("weights");
        double stockAfter = 0;
        List<ObjectNode> sells = new ArrayList<>();
        List<ObjectNode> buys = new ArrayList<>();
        for (String c : codes) {
            int q = qty.getOrDefault(c, 0);
            double px = close(c);
            int tq = px > 0 ? (int) Math.floor(target.getOrDefault(c, 0.0) * total / px + 1e-9) : q;
            if (tq > 0) {
                afterQty.put(c, tq);
                stockAfter += tq * px;
                weights.put(c, r(tq * px / total));
            }
            if (tq != q) {
                ObjectNode t = json.createObjectNode();
                t.put("asset", c).put("side", tq > q ? "BUY" : "SELL").put("quantity", Math.abs(tq - q))
                        .put("close", px).put("amount", Math.round(Math.abs(tq - q) * px));
                (tq > q ? buys : sells).add(t);
            }
        }
        double bondAfter = target.getOrDefault("BOND", 0.0) * total;
        double bondNow = req.bondUnits() * BOND_INDEX;
        if (Math.abs(bondAfter - bondNow) >= 1) {
            ObjectNode t = json.createObjectNode();
            t.put("asset", "BOND").put("side", bondAfter > bondNow ? "BUY" : "SELL")
                    .put("amount", Math.round(Math.abs(bondAfter - bondNow)))
                    .put("units", Math.abs(bondAfter - bondNow) / BOND_INDEX);
            (bondAfter > bondNow ? buys : sells).add(t);
        }
        Comparator<ObjectNode> byAmount = Comparator.comparing(t -> -t.path("amount").asLong());
        sells.stream().sorted(byAmount).forEach(trades::add);
        buys.stream().sorted(byAmount).forEach(trades::add);
        double cashAfter = total - stockAfter - bondAfter;
        double stockNow = qty.entrySet().stream().mapToDouble(e -> e.getValue() * close(e.getKey())).sum();
        out.putObject("before").putObject("by_asset").put("stock", r(stockNow / total))
                .put("bond", r(bondNow / total)).put("cash", r(req.cash() / total));
        after.put("bond_units", bondAfter / BOND_INDEX);
        after.put("cash", Math.round(cashAfter));
        after.putObject("by_asset").put("stock", r(stockAfter / total)).put("bond", r(bondAfter / total))
                .put("cash", r(cashAfter / total));
        weights.put("BOND", r(bondAfter / total));
        weights.put("CASH", r(cashAfter / total));
        out.put("as_of", stocks.asOf().toString());
        out.put("data_version", stocks.dataVersion());
        out.put("bond_index", BOND_INDEX);
        out.put("alert_type", req.alertType());
        out.put("alert_target", req.alertTarget());
        return json.writeValueAsString(out);
    }

    private double close(String code) {
        Double c = byCode.get(code).lastClose();
        return c == null ? 0.0 : c;
    }

    private static ObjectNode condition(ArrayNode conditions, String type, String target) {
        ObjectNode c = conditions.addObject();
        c.put("type", type);
        c.put("target", target);
        return c.putObject("detail");
    }

    private static double ratio(double v, double total) {
        return total > 0 ? v / total : 0.0;
    }

    private static double r(double x) {
        return Math.round(x * 10_000) / 10_000.0;
    }
}
