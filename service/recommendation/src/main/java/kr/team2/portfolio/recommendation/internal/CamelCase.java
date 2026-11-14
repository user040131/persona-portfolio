package kr.team2.portfolio.recommendation.internal;

import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 엔진 응답(snake_case) → 공개 API(camelCase) (docs/03 §2.1).
 * 소문자로 시작하는 snake_case 키만 바꾼다. 종목코드(005930)·팩터(VALUE)·BOND 같은 데이터 키는 그대로 둔다.
 */
final class CamelCase {

    private static final Pattern SNAKE = Pattern.compile("[a-z][a-z0-9]*(_[a-z0-9]+)+");

    private CamelCase() {
    }

    static JsonNode convert(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                out.set(key(e.getKey()), convert(e.getValue()));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(n -> out.add(convert(n)));
            return out;
        }
        return node;
    }

    static String key(String k) {
        if (!SNAKE.matcher(k).matches()) {
            return k;
        }
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char ch : k.toCharArray()) {
            if (ch == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
        }
        return sb.toString();
    }
}
