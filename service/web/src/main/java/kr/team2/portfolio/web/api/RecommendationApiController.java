package kr.team2.portfolio.web.api;

import kr.team2.portfolio.auth.api.AuthenticatedUser;
import kr.team2.portfolio.recommendation.api.EditCommand;
import kr.team2.portfolio.recommendation.api.RecommendationFacade;
import kr.team2.portfolio.recommendation.api.WhyNot;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** 추천 API (docs/03 §7.2 #10~15). */
@RestController
@RequestMapping("/api/recommendations")
class RecommendationApiController {

    private final RecommendationFacade recommendations;

    RecommendationApiController(RecommendationFacade recommendations) {
        this.recommendations = recommendations;
    }

    /** @param investAmount 비우면 온보딩 때 입력한 금액 */
    record CreateRequest(Long investAmount) {
    }

    record PurchaseRequest(int version) {
    }

    record PurchaseResponse(long recommendationId, int version, boolean purchased) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ObjectNode create(@AuthenticationPrincipal AuthenticatedUser user,
                      @RequestBody(required = false) CreateRequest req) {
        return recommendations.create(user.id(), req == null ? null : req.investAmount());
    }

    @GetMapping("/{id}")
    ObjectNode get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                   @RequestParam(required = false) Integer version) {
        return recommendations.get(user.id(), id, version);
    }

    @PostMapping("/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    ObjectNode edit(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                    @RequestBody EditCommand req) {
        return recommendations.edit(user.id(), id, req);
    }

    @GetMapping("/{id}/why-not/{code}")
    WhyNot whyNot(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id, @PathVariable String code) {
        return recommendations.whyNot(user.id(), id, code);
    }

    /** 과거 성과 카드 (#14). 엔진 응답 그대로. */
    @GetMapping("/{id}/history-card")
    org.springframework.http.ResponseEntity<String> historyCard(@AuthenticationPrincipal AuthenticatedUser user,
                                                                @PathVariable long id) {
        return BacktestApiController.json(recommendations.historyCard(user.id(), id));
    }

    @PostMapping("/{id}/purchase")
    PurchaseResponse purchase(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                              @RequestBody PurchaseRequest req) {
        recommendations.purchase(user.id(), id, req.version());
        return new PurchaseResponse(id, req.version(), true);
    }
}
