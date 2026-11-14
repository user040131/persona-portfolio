package kr.team2.portfolio.recommendation.api;

/** 매수 완료 이벤트 → alert 모듈이 첫 알림을 평가한다. */
public record PurchaseCompleted(long userId, long recommendationId, int version) {
}
