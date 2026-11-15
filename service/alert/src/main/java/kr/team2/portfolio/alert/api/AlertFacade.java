package kr.team2.portfolio.alert.api;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 리밸런싱 알림 상태와 제안 (docs/03 §6.5, 01 FR-ALT-*). 조건 판정은 엔진(I8)이 하고, 여기서는 상태만 맞춘다.
 */
public interface AlertFacade {

    /**
     * 엔진이 판정한 조건 목록에 알림 상태를 맞춘다. 포트폴리오를 볼 때마다 부른다 (FR-ALT-01).
     * <ul>
     *   <li>조건이 사라진 열린 알림(OPEN·DISMISSED) → CLOSED. 단 R6(연 1회)은 사용자가 닫거나 반영할 때만 닫힌다</li>
     *   <li>새 조건 → OPEN. R6가 새로 열리면 포트폴리오에 그 해를 적는다</li>
     *   <li>이미 열린 조건 → detail만 갱신 (닫은 알림은 닫힌 채로)</li>
     * </ul>
     *
     * @param conditions 엔진 I8의 {@code conditions} 배열
     * @return 지금 OPEN인 알림 (화면에 보일 것)
     */
    List<AlertView> sync(long userId, JsonNode conditions);

    /** @param status null이면 OPEN */
    List<AlertView> list(long userId, String status);

    AlertView get(long userId, long alertId);

    /** 닫기 (FR-ALT-08): OPEN → DISMISSED. 조건이 사라졌다 다시 생기면 새로 열린다. R6은 바로 CLOSED. */
    void dismiss(long userId, long alertId);

    /** 리밸런싱 제안 (엔진 I9 + 종목명·문구, FR-ALT-09). */
    ObjectNode suggestion(long userId, long alertId);

    /**
     * 제안대로 했다고 기록: 제안을 다시 계산해 포트폴리오에 반영하고 알림을 닫는다.
     *
     * @param traded 실제로 사고판 양 (종목은 주, 채권은 원). 제안의 사기·팔기 방향은 그대로 쓴다.
     *               null이면 제안 수량 그대로. 제안에 없는 자산은 무시한다.
     * @throws kr.team2.portfolio.common.BusinessException ALERT_CLOSED, VALIDATION_ERROR(가진 것보다 많이 팔거나 현금이 모자람)
     */
    void apply(long userId, long alertId, Map<String, Long> traded);
}
