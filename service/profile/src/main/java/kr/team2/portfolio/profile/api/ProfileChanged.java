package kr.team2.portfolio.profile.api;

/** 재진단 이벤트 → alert 모듈이 알림을 다시 평가한다. */
public record ProfileChanged(long userId, long profileId) {
}
