package kr.team2.portfolio.profile.api;

import java.util.Optional;

/** 다른 모듈과 web이 프로필을 쓰는 창구 (docs/03 §6.1). */
public interface ProfileFacade {

    /** 설문 정의 (화면용). */
    Survey survey();

    /** 최소 투자 금액 (원, 결정 2-3). */
    long minInvestAmount();

    /** 첫 추천에 쓸 온보딩 투자 금액. 매수 완료 후에는 비어 있다. */
    Optional<Long> pendingInvestAmount(long userId);

    /**
     * 온보딩: 프로필 v1 저장, 투자 금액 보관, {@link OnboardingCompleted} 발행.
     *
     * @throws kr.team2.portfolio.common.BusinessException ALREADY_ONBOARDED, VALIDATION_ERROR,
     *                                                     INVALID_SURVEY, UNKNOWN_STOCK
     */
    ProfileView onboard(long userId, OnboardingCommand command);

    /**
     * 처음부터 다시: 투자 금액·설문·보유 종목을 새로 받는다. 프로필은 새 버전으로 저장하고
     * {@link OnboardingCompleted}를 다시 내서 포트폴리오를 입력한 보유로 새로 채운다 (매수 기록도 지운다).
     *
     * @throws kr.team2.portfolio.common.BusinessException PROFILE_NOT_FOUND, VALIDATION_ERROR,
     *                                                     INVALID_SURVEY, UNKNOWN_STOCK
     */
    ProfileView restart(long userId, OnboardingCommand command);

    /** 재진단: 새 버전으로 저장하고 {@link ProfileChanged} 발행. 보유·투자 금액은 그대로. */
    ProfileView resurvey(long userId, Answers answers);

    Optional<ProfileView> current(long userId);

    /** 지난 버전을 포함한 특정 프로필. 추천 문구는 추천을 만든 때의 프로필로 쓴다. */
    Optional<ProfileView> find(long userId, long profileId);

    /** 매수 완료 시 첫 추천 금액을 비운다 (docs/03 §6.4 6단계). 호출한 쪽 트랜잭션에 참여한다. */
    void clearPendingInvestAmount(long userId);

    boolean isOnboarded(long userId);

    /** 현재 프로필 설정값. 온보딩 전이면 비어 있다. */
    Optional<ProfileSettings> currentSettings(long userId);

    /** 설문 답 → 설정값 (저장하지 않음). 백테스트 대표 프로필 계산에도 쓴다. */
    ProfileSettings calculate(Answers answers);
}
