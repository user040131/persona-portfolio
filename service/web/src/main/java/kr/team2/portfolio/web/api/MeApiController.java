package kr.team2.portfolio.web.api;

import kr.team2.portfolio.auth.api.AuthenticatedUser;
import kr.team2.portfolio.portfolio.api.PortfolioFacade;
import kr.team2.portfolio.profile.api.ProfileFacade;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 03 §7.2 #1. 사용자, 온보딩·포트폴리오(매수 완료) 여부. */
@RestController
@RequestMapping("/api")
class MeApiController {

    private final ProfileFacade profiles;
    private final PortfolioFacade portfolios;

    MeApiController(ProfileFacade profiles, PortfolioFacade portfolios) {
        this.profiles = profiles;
        this.portfolios = portfolios;
    }

    record Me(long id, String username, boolean onboarded, boolean purchased) {
    }

    @GetMapping("/me")
    Me me(@AuthenticationPrincipal AuthenticatedUser user) {
        return new Me(user.id(), user.getUsername(), profiles.isOnboarded(user.id()),
                portfolios.hasPurchased(user.id()));
    }
}
