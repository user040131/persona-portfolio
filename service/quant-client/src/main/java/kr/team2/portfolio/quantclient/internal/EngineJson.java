package kr.team2.portfolio.quantclient.internal;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** 엔진으로 보내는 JSON은 snake_case (docs/03 §2.1). */
final class EngineJson {

    private static final JsonMapper SNAKE = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    private EngineJson() {
    }

    static String write(Object value) {
        return SNAKE.writeValueAsString(value);
    }
}
