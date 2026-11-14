package kr.team2.portfolio.recommendation.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import kr.team2.portfolio.common.BusinessException;
import kr.team2.portfolio.common.ErrorCode;
import kr.team2.portfolio.profile.api.Method;
import kr.team2.portfolio.recommendation.api.EditCommand;

/**
 * 한 버전까지 누적된 수정 (docs/03 §6.2). {@code recommendation_version.edits}에 이 모양으로 저장한다.
 *
 * @param safeRatio null이면 엔진이 자동으로 정한다
 * @param method    null이면 프로필 기본 방식
 */
record Edits(List<String> removedCodes, Double safeRatio, Method method) {

    static final Edits NONE = new Edits(List.of(), null, null);

    Edits {
        removedCodes = removedCodes == null ? List.of() : List.copyOf(removedCodes);
    }

    /**
     * 직전 수정에 이번 요청을 누적한다. removedCodes는 합집합(먼저 뺀 순서 유지),
     * safeRatio·method는 덮어쓰기, null은 유지, safeRatio = -1이면 해제.
     */
    Edits merge(EditCommand cmd) {
        LinkedHashSet<String> removed = new LinkedHashSet<>(removedCodes);
        if (cmd.removedCodes() != null) {
            cmd.removedCodes().stream().map(String::trim).filter(c -> !c.isEmpty()).forEach(removed::add);
        }
        Double ratio = safeRatio;
        if (cmd.safeRatio() != null) {
            double r = cmd.safeRatio();
            if (r == EditCommand.CLEAR) {
                ratio = null;
            } else if (r >= 0 && r <= 1) {
                ratio = r;
            } else {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        Map.of("field", "safeRatio", "allowed", "0~1 또는 -1"));
            }
        }
        Method m = cmd.method() != null ? cmd.method() : method;
        return new Edits(new ArrayList<>(removed), ratio, m);
    }
}
