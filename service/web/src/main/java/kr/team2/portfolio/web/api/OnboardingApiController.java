package kr.team2.portfolio.web.api;

import java.util.List;
import kr.team2.portfolio.auth.api.AuthenticatedUser;
import kr.team2.portfolio.common.BusinessException;
import kr.team2.portfolio.common.ErrorCode;
import kr.team2.portfolio.profile.api.Answers;
import kr.team2.portfolio.profile.api.OnboardingCommand;
import kr.team2.portfolio.profile.api.ProfileFacade;
import kr.team2.portfolio.profile.api.ProfileView;
import kr.team2.portfolio.profile.api.Survey;
import kr.team2.portfolio.quantclient.api.StockCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 온보딩·프로필 API (docs/03 §7.2 #6~9). */
@RestController
@RequestMapping("/api")
class OnboardingApiController {

    private final ProfileFacade profiles;
    private final StockCatalog catalog;

    OnboardingApiController(ProfileFacade profiles, StockCatalog catalog) {
        this.profiles = profiles;
        this.catalog = catalog;
    }

    record SurveyResponse(Survey survey, List<String> sectors, long minInvestAmount) {
    }

    /** 온보딩 요청 본문 (docs/03 §7.3 #7). */
    record OnboardingRequest(long investAmount, List<Integer> q, int a2, boolean a3, int a4, int a5,
                             List<String> a6, List<String> a7, List<OnboardingCommand.HoldingInput> holdings) {

        Answers answers() {
            return new Answers(q, a2, a3, a4, a5, a6, a7);
        }
    }

    @GetMapping("/onboarding/survey")
    SurveyResponse survey() {
        return new SurveyResponse(profiles.survey(), catalog.sectors(), profiles.minInvestAmount());
    }

    @PostMapping("/onboarding")
    @ResponseStatus(HttpStatus.CREATED)
    ProfileView onboard(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody OnboardingRequest req) {
        return profiles.onboard(user.id(), new OnboardingCommand(
                req.investAmount(), req.answers(), req.holdings() == null ? List.of() : req.holdings()));
    }

    @GetMapping("/profile")
    ProfileView profile(@AuthenticationPrincipal AuthenticatedUser user) {
        return profiles.current(user.id()).orElseThrow(() -> new BusinessException(ErrorCode.PROFILE_NOT_FOUND));
    }

    @PutMapping("/profile/survey")
    ProfileView resurvey(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody Answers answers) {
        return profiles.resurvey(user.id(), answers);
    }
}
