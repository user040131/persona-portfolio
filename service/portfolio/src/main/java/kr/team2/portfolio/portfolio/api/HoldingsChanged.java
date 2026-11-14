package kr.team2.portfolio.portfolio.api;

/** 보유 수정 이벤트 → alert 모듈이 알림을 다시 평가한다. */
public record HoldingsChanged(long userId) {
}
