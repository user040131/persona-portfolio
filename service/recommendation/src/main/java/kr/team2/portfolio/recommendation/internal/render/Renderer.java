package kr.team2.portfolio.recommendation.internal.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import kr.team2.portfolio.common.Texts;
import tools.jackson.databind.JsonNode;

/**
 * 엔진이 준 사실(코드·숫자, docs/03 §1.9)을 한국어 문장으로 만든다 (docs/03 §6.3).
 * 입력은 엔진 응답 그대로(snake_case)다. 프로필 답은 {@link ProfileContext}로 받는다.
 */
public final class Renderer {

    private static final double EPS = 0.005;

    private final Texts texts;

    public Renderer(Texts texts) {
        this.texts = texts;
    }

    /**
     * 문장을 만들 때 필요한 프로필 정보.
     *
     * @param a2Text     A2 선택지 문구 (1년 안에 쓸 돈)
     * @param a2None     A2가 "없음"
     * @param emergency  A3 비상금 있음
     * @param a5Text     A5 선택지 문구 (종목 수)
     * @param a5Any      A5가 "상관없음"
     * @param userMethod 비중 방식을 사용자가 직접 골랐음
     */
    public record ProfileContext(String gradeLabel, String a2Text, boolean a2None, boolean emergency,
                                 String a5Text, boolean a5Any, boolean userMethod) {
    }

    /** 종목 근거 한 줄. type은 SELECTION · LEVEL · PROFILE_LINK · CAUTION · ROLE. */
    public record Reason(String type, String text) {
    }

    // ---------------------------------------------------------------- 라벨

    public String factorLabel(String factor) {
        return texts.rawOr("factor." + factor, factor);
    }

    public String methodLabel(String method) {
        return texts.rawOr("method." + method, method);
    }

    public String groupLabel(String group) {
        return texts.rawOr("group." + group, group);
    }

    public Map<String, String> labels(String prefix, List<String> keys) {
        Map<String, String> out = new LinkedHashMap<>();
        keys.forEach(k -> out.put(k, texts.rawOr(prefix + "." + k, k)));
        return out;
    }

    // ---------------------------------------------------------------- 종목 근거

    public List<Reason> stockReasons(JsonNode stock, String gradeLabel) {
        JsonNode facts = stock.path("facts");
        List<Reason> out = new ArrayList<>();

        JsonNode sel = facts.path("selection");
        List<String> top = strings(sel.path("top_factors"));
        if (!top.isEmpty()) {
            out.add(new Reason("SELECTION", texts.text("stock.selection", Map.of(
                    "group", groupLabel(sel.path("group").asString()),
                    "groupN", sel.path("group_n").asInt(),
                    "rankInGroup", sel.path("rank_in_group").asInt(),
                    "factors", top.stream().map(this::factorLabel).collect(Collectors.joining("·"))))));
        }

        JsonNode level = facts.path("level");
        List<String> parts = new ArrayList<>();
        for (String k : List.of("per", "pbr", "roe")) {
            Double v = number(level.path(k));
            if (v != null) {
                parts.add(texts.text("stock.level." + k, Map.of(k, v)));
            }
        }
        if (!parts.isEmpty()) {
            out.add(new Reason("LEVEL", String.join(" · ", parts)));
        }

        JsonNode link = facts.path("profile_link");
        if (link.isObject()) {
            String factor = link.path("factor").asString();
            String key = "stock.link." + factor;
            Map<String, Object> vars = new HashMap<>();
            vars.put("gradeLabel", gradeLabel);
            vars.put("pctTop", number(link.path("pct_top")));
            vars.put("value", number(link.path("value")));
            boolean needsValue = factor.equals("MOMENTUM") || factor.equals("DIVIDEND");
            Object needed = needsValue ? vars.get("value") : vars.get("pctTop");
            if (texts.has(key) && needed != null) {
                out.add(new Reason("PROFILE_LINK", texts.text(key, vars)));
            }
        }

        for (JsonNode c : facts.path("cautions")) {
            String code = c.path("code").asString();
            Map<String, Object> vars = new HashMap<>();
            vars.put("vol1y", number(c.path("vol_1y")));
            vars.put("rsi14", number(c.path("rsi_14")));
            vars.put("factorLabel", factorLabel(c.path("factor").asString("")));
            if (texts.has("stock.caution." + code)) {
                out.add(new Reason("CAUTION", texts.text("stock.caution." + code, vars)));
            }
        }

        JsonNode role = facts.path("role");
        String roleKey = "stock.role." + role.path("code").asString("");
        if (texts.has(roleKey)) {
            out.add(new Reason("ROLE", texts.text(roleKey, Map.of("sector", role.path("sector").asString("")))));
        }
        return out;
    }

    // ---------------------------------------------------------------- 포트폴리오 근거

    public List<String> allocationReasons(JsonNode result, ProfileContext p) {
        JsonNode a = result.path("allocation");
        JsonNode f = result.path("portfolio_facts");
        double stock = a.path("stock").asDouble();
        double bond = a.path("bond").asDouble();
        double cash = a.path("cash").asDouble();
        double minCash = f.path("min_cash").asDouble();
        String binding = f.path("stock_binding").asString("");
        int nFinal = f.path("n_final").asInt();
        List<String> out = new ArrayList<>();

        // 현금
        boolean extra = cash > minCash + EPS;
        StringBuilder c = new StringBuilder();
        if (!p.a2None()) {
            c.append(texts.text("alloc.cash", Map.of("cash", cash, "a2Text", p.a2Text())));
        } else if (p.emergency() && !extra) {
            c.append(texts.text("alloc.cash.none", Map.of("cash", cash)));
        } else {
            c.append(texts.text("alloc.cash.noneBut", Map.of("cash", cash)));
        }
        if (!p.emergency()) {
            c.append(texts.text("alloc.cash.noEmergency"));
        }
        if (extra && texts.has("alloc.cash.extra." + binding)) {
            c.append(texts.text("alloc.cash.extra." + binding, Map.of("extra", cash - minCash)));
        }
        out.add(c.toString());

        if (bond > 0) {
            out.add(texts.text("alloc.bond", Map.of("bond", bond)));
        }

        // 주식 비중을 정한 이유
        Map<String, Object> vars = new HashMap<>();
        vars.put("lossLimit", number(f.path("loss_limit")));
        vars.put("stock", stock);
        String start = a.path("lookback").path("start").asString("");
        vars.put("startYear", start.length() >= 4 ? start.substring(0, 4) : "");
        vars.put("expectedMdd", number(a.path("expected_mdd")));
        vars.put("stockCap", number(f.path("stock_cap")));
        vars.put("gradeLabel", p.gradeLabel());
        switch (binding) {
            case "LOSS_LIMIT" -> out.add(texts.text(
                    stock > 0 ? "alloc.binding.LOSS_LIMIT" : "alloc.binding.LOSS_LIMIT.noStock", vars));
            case "CAP" -> out.add(texts.text("alloc.binding.CAP", vars));
            case "ALL_CASH" -> out.add(texts.text("alloc.binding.ALL_CASH", vars));
            case "USER" -> out.add(texts.text("alloc.binding.USER", vars)
                    + (hasWarning(result, "LOSS_LIMIT_EXCEEDED") ? texts.text("alloc.binding.USER.exceeded") : ""));
            default -> { }
        }

        // 종목 수·비중 방식 (주식이 있을 때만)
        if (nFinal > 0) {
            String count = p.a5Any()
                    ? texts.text("alloc.count.any", Map.of("nFinal", nFinal))
                    : texts.text("alloc.count", Map.of("nFinal", nFinal, "a5Text", p.a5Text()));
            if (f.path("n_reduced").asBoolean()) {
                count += texts.text("alloc.count.reduced");
            }
            out.add(count);
            String method = methodLabel(result.path("method_selected").asString());
            out.add(p.userMethod()
                    ? texts.text("alloc.method.user", Map.of("methodLabel", method))
                    : texts.text("alloc.method", Map.of("methodLabel", method, "gradeLabel", p.gradeLabel())));
        }

        // 빠진 종목 요약. MySQL JSON은 키 순서를 바꿔 저장하므로 사유 코드 순으로 정렬한다
        List<String> items = new ArrayList<>();
        f.path("skipped").properties().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> items.add(texts.text("alloc.skipped.item", Map.of(
                        "label", texts.rawOr("skipped." + e.getKey(), e.getKey()), "count", e.getValue().asInt()))));
        if (!items.isEmpty()) {
            out.add(texts.text("alloc.skipped", Map.of("items", String.join(", ", items))));
        }

        long rounding = a.path("cash_from_rounding").asLong();
        if (rounding > 0) {
            out.add(texts.text("alloc.rounding", Map.of("cashFromRounding", rounding)));
        }
        return out;
    }

    // ---------------------------------------------------------------- 경고·왜 없나요

    /** 경고 문구. 모르는 경고면 null. */
    public String warning(JsonNode w) {
        String key = "warning." + w.path("type").asString("");
        if (!texts.has(key)) {
            return null;
        }
        JsonNode d = w.path("detail");
        Map<String, Object> vars = new HashMap<>();
        vars.put("expectedMdd", number(d.path("expected_mdd")));
        vars.put("lossLimit", number(d.path("loss_limit")));
        vars.put("nRequested", d.path("n_requested").asInt());
        vars.put("nFinal", d.path("n_final").asInt());
        return texts.text(key, vars);
    }

    /** 왜 없나요 문구. reason이 null이면 추천에 들어 있다는 뜻이다. */
    public String whyNot(String reason, JsonNode params, String gradeLabel) {
        if (reason == null) {
            return texts.text("whynot.PICKED");
        }
        String key = "whynot." + reason;
        if (!texts.has(key)) {
            return reason;
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("gradeLabel", gradeLabel);
        vars.put("rules", strings(params.path("rules")).stream()
                .map(r -> r + " " + texts.rawOr("delist." + r, ""))
                .map(String::trim)
                .collect(Collectors.joining(", ")));
        vars.put("pct", number(params.path("pct")));
        vars.put("rsi14", number(params.path("rsi_14")));
        vars.put("limit", number(params.path("limit")));
        vars.put("close", number(params.path("close")));
        vars.put("group", groupLabel(params.path("group").asString("")));
        vars.put("groupN", params.path("group_n").asInt());
        vars.put("rankInGroup", params.path("rank_in_group").asInt());
        return texts.text(key, vars);
    }

    // ---------------------------------------------------------------- 도우미

    private static boolean hasWarning(JsonNode result, String type) {
        return StreamSupport.stream(result.path("warnings").spliterator(), false)
                .anyMatch(w -> type.equals(w.path("type").asString("")));
    }

    private static Double number(JsonNode n) {
        return n.isNumber() ? n.asDouble() : null;
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asString()));
        return out;
    }
}
