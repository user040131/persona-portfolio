package kr.team2.portfolio.recommendation.api;

import java.util.Optional;
import tools.jackson.databind.node.ObjectNode;

/**
 * 추천 요청·버전·왜 없나요·매수 완료 (docs/03 §6.2~6.4). web이 쓰는 창구.
 * 조회 결과는 엔진 I6 응답을 camelCase로 바꾸고 문구를 덧붙인 JSON이다 (docs/03 §7.3 #10~12).
 */
public interface RecommendationFacade {

    /**
     * 추천 생성 → version 1.
     *
     * @param investAmount null이면 온보딩 때 받은 금액(매수 완료 전 1회만)
     * @throws kr.team2.portfolio.common.BusinessException PROFILE_NOT_FOUND, VALIDATION_ERROR, QUANT_UNAVAILABLE 등
     */
    ObjectNode create(long userId, Long investAmount);

    /** @param version null이면 최신 버전 */
    ObjectNode get(long userId, long recommendationId, Integer version);

    /** 수정 → 새 버전 (직전 수정에 누적). */
    ObjectNode edit(long userId, long recommendationId, EditCommand command);

    WhyNot whyNot(long userId, long recommendationId, String code);

    /** 매수 완료 → 포트폴리오 반영, {@link PurchaseCompleted} 발행. */
    void purchase(long userId, long recommendationId, int version);

    /**
     * 과거 성과 카드 (01 FR-REC-14): 이 추천을 만든 프로필 설정으로 2015-04부터 실험 D 로직을 운용한 결과.
     * 엔진 응답 JSON(snake_case) 그대로. 처음 한 번은 몇 초 걸리고, 같은 설정이면 엔진이 캐시한다.
     */
    String historyCard(long userId, long recommendationId);

    /** 가장 최근 추천. */
    Optional<Long> latestId(long userId);
}
