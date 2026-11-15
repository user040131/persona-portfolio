package kr.team2.portfolio.web.api;

import java.util.List;
import java.util.Map;
import kr.team2.portfolio.alert.api.AlertFacade;
import kr.team2.portfolio.alert.api.AlertView;
import kr.team2.portfolio.auth.api.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** 알림 API (docs/03 §7.2 #19~21) + 제안대로 반영. */
@RestController
@RequestMapping("/api/alerts")
class AlertApiController {

    private final AlertFacade alerts;

    AlertApiController(AlertFacade alerts) {
        this.alerts = alerts;
    }

    /** 저장된 상태만 읽는다 (엔진 호출 없음). 최신 상태는 GET /api/portfolio가 맞춘다. */
    @GetMapping
    List<AlertView> list(@AuthenticationPrincipal AuthenticatedUser user,
                         @RequestParam(required = false) String status) {
        return alerts.list(user.id(), status);
    }

    @PostMapping("/{id}/dismiss")
    AlertView dismiss(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        alerts.dismiss(user.id(), id);
        return alerts.get(user.id(), id);
    }

    @GetMapping("/{id}/suggestion")
    ObjectNode suggestion(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return alerts.suggestion(user.id(), id);
    }

    /** 사고판 양을 직접 넣을 때의 본문. 없으면 제안대로. */
    record ApplyRequest(Map<String, Long> traded) {
    }

    /** 제안대로(또는 직접 넣은 수량대로) 했다고 기록 → 포트폴리오 반영, 알림 닫힘. */
    @PostMapping("/{id}/apply")
    Map<String, Object> apply(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                              @RequestBody(required = false) ApplyRequest req) {
        alerts.apply(user.id(), id, req == null ? null : req.traded());
        return Map.of("alertId", id, "applied", true);
    }
}
