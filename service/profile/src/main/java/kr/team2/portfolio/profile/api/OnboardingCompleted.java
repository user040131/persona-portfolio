package kr.team2.portfolio.profile.api;

import java.util.List;

/** 온보딩 완료 이벤트 → portfolio 모듈이 기존 보유로 포트폴리오를 만든다 (docs/02 §3.3). */
public record OnboardingCompleted(long userId, long profileId, List<ExistingHolding> holdings) {

    public record ExistingHolding(String code, int quantity) {
    }
}
