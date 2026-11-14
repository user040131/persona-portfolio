package kr.team2.portfolio.recommendation.api;

import java.util.List;
import kr.team2.portfolio.profile.api.Method;

/**
 * 추천 수정 요청 (docs/03 §6.2). 모두 비워도 되고, 비운 값은 직전 버전 그대로다.
 *
 * @param removedCodes 더 뺄 종목 (이미 뺀 것과 합쳐진다)
 * @param safeRatio    안전자산(채권+현금) 비중 0~1. {@code -1}이면 직접 정한 비중을 해제
 * @param method       주식 안 비중 방식
 */
public record EditCommand(List<String> removedCodes, Double safeRatio, Method method) {

    public static final double CLEAR = -1;
}
