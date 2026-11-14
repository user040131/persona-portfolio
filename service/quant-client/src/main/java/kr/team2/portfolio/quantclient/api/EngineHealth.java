package kr.team2.portfolio.quantclient.api;

/** I1 응답. 엔진 원문은 {@code {"status": "ok", "data_loaded": true}}. */
public record EngineHealth(String status, boolean dataLoaded) {
}
