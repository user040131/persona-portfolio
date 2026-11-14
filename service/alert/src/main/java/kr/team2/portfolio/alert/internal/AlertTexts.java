package kr.team2.portfolio.alert.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import kr.team2.portfolio.alert.api.AlertType;
import kr.team2.portfolio.common.Texts;
import tools.jackson.databind.JsonNode;

/** 알림 문구 (docs/03 §6.3, {@code messages_alerts.properties}). */
final class AlertTexts {

    private final Texts texts;
    private final Function<String, String> stockName;

    AlertTexts(Texts texts, Function<String, String> stockName) {
        this.texts = texts;
        this.stockName = stockName;
    }

    String title(AlertType type) {
        return texts.raw("title." + type);
    }

    String help(AlertType type) {
        return texts.raw("help." + type);
    }

    String side(String side) {
        return texts.rawOr("side." + side, side);
    }

    /** 종목코드 → 이름, BOND·CASH → 채권·현금. */
    String assetName(String asset) {
        return texts.has("asset." + asset) ? texts.raw("asset." + asset) : stockName.apply(asset);
    }

    String text(AlertType type, String target, JsonNode d) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("target", target);
        vars.put("name", target.isEmpty() ? "" : stockName.apply(target));
        vars.put("assetName", target.isEmpty() ? "" : assetName(target));
        for (String k : List.of("weight", "limit", "current", "target", "drawdown")) {
            if (d.path(k).isNumber()) {
                vars.put(k, d.path(k).asDouble());
            }
        }
        vars.put("year", d.path("year").asInt());
        List<String> rules = new ArrayList<>();
        d.path("rules").forEach(r -> rules.add((r.asString() + " " + texts.rawOr("delist." + r.asString(), "")).trim()));
        vars.put("rules", String.join(", ", rules));
        if (type == AlertType.R3 || type == AlertType.R4) {
            vars.put("target", d.path("target").asDouble());   // R3·R4의 {target}은 목표 비중
        }
        return texts.text("alert." + type, vars);
    }
}
