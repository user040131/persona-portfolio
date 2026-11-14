package kr.team2.portfolio.web.api;

import kr.team2.portfolio.alert.api.AlertFacade;
import kr.team2.portfolio.auth.api.AuthenticatedUser;
import kr.team2.portfolio.common.BusinessException;
import kr.team2.portfolio.common.ErrorCode;
import kr.team2.portfolio.portfolio.api.PortfolioFacade;
import kr.team2.portfolio.portfolio.api.PortfolioUpdate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 포트폴리오 API (docs/03 §7.2 #16~18). 평가할 때마다 엔진이 판정한 조건으로 알림 상태를 맞추고(FR-ALT-01),
 * 열린 알림을 {@code alerts}로 붙인다 (docs/02 §5.2).
 */
@RestController
@RequestMapping("/api/portfolio")
class PortfolioApiController {

    private final PortfolioFacade portfolios;
    private final AlertFacade alerts;
    private final JsonMapper json;

    PortfolioApiController(PortfolioFacade portfolios, AlertFacade alerts, JsonMapper json) {
        this.portfolios = portfolios;
        this.alerts = alerts;
        this.json = json;
    }

    @GetMapping
    ObjectNode get(@AuthenticationPrincipal AuthenticatedUser user) {
        return withAlerts(user.id(), portfolios.evaluate(user.id()));
    }

    private ObjectNode withAlerts(long userId, ObjectNode evaluation) {
        evaluation.set("alerts", json.valueToTree(alerts.sync(userId, evaluation.path("conditions"))));
        return evaluation;
    }

    /** 종목 상세 (FR-PF-03): 평가금액·수량·평균 매입가·수익률·목표 비중·현재 비중. */
    @GetMapping("/stocks/{code}")
    JsonNode stock(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String code) {
        for (JsonNode s : portfolios.evaluate(user.id()).path("stocks")) {
            if (s.path("code").asString().equals(code)) {
                return s;
            }
        }
        throw new BusinessException(ErrorCode.STOCK_NOT_FOUND);
    }

    /** 수정한 뒤의 평가를 돌려준다. */
    @PatchMapping
    ObjectNode update(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody PortfolioUpdate req) {
        portfolios.update(user.id(), req);
        return withAlerts(user.id(), portfolios.evaluate(user.id()));
    }
}
