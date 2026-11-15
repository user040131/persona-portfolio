package kr.team2.portfolio.web.page;

import java.util.List;
import kr.team2.portfolio.auth.api.AuthenticatedUser;
import kr.team2.portfolio.portfolio.api.HoldingsSnapshot;
import kr.team2.portfolio.portfolio.api.PortfolioFacade;
import kr.team2.portfolio.profile.api.ProfileFacade;
import kr.team2.portfolio.profile.api.ProfileView;
import kr.team2.portfolio.quantclient.api.StockCatalog;
import kr.team2.portfolio.quantclient.api.StockInfo;
import kr.team2.portfolio.recommendation.api.RecommendationFacade;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import tools.jackson.databind.json.JsonMapper;

/**
 * 화면 진입점 (docs/03 §7.1).
 */
@Controller
class PageController {

    private final ProfileFacade profiles;
    private final PortfolioFacade portfolios;
    private final StockCatalog catalog;
    private final RecommendationFacade recommendations;
    private final JsonMapper json;

    PageController(ProfileFacade profiles, PortfolioFacade portfolios, StockCatalog catalog,
                   RecommendationFacade recommendations, JsonMapper json) {
        this.profiles = profiles;
        this.portfolios = portfolios;
        this.catalog = catalog;
        this.recommendations = recommendations;
        this.json = json;
    }

    @GetMapping("/login")
    String login() {
        return "login";
    }

    /** 온보딩 전 → 온보딩, 매수 완료 후 → 포트폴리오, 추천이 있으면 최근 추천, 없으면 프로필. */
    @GetMapping("/")
    String home(@AuthenticationPrincipal AuthenticatedUser user) {
        if (!profiles.isOnboarded(user.id())) {
            return "redirect:/onboarding";
        }
        if (portfolios.hasPurchased(user.id())) {
            return "redirect:/portfolio";
        }
        return recommendations.latestId(user.id())
                .map(id -> "redirect:/recommendations/" + id)
                .orElse("redirect:/profile");
    }

    /** 내 포트폴리오 (매수 완료 후). 데이터는 화면이 /api/portfolio로 읽는다. */
    @GetMapping("/portfolio")
    String portfolio(@AuthenticationPrincipal AuthenticatedUser user, Model model) {
        if (!portfolios.hasPurchased(user.id())) {
            return "redirect:/";
        }
        model.addAttribute("minInvestAmount", profiles.minInvestAmount());
        return "portfolio";
    }

    /** 백테스트 목록·실행 (docs/03 §7.1). */
    @GetMapping("/backtests")
    String backtests() {
        return "backtests";
    }

    /** 백테스트 결과. 화면이 3초마다 /api/backtests/{jobId}로 상태를 읽는다 (FR-BT-13). */
    @GetMapping("/backtests/{jobId}")
    String backtest(@PathVariable String jobId, Model model) {
        model.addAttribute("jobId", jobId);
        return "backtest";
    }

    /** 리밸런싱 제안. 데이터는 화면이 /api/alerts/{id}/suggestion으로 읽는다 (본인 것이 아니면 404). */
    @GetMapping("/alerts/{id}")
    String alert(@PathVariable long id, Model model) {
        model.addAttribute("alertId", id);
        return "alert";
    }

    /** 추천 결과. 데이터는 화면이 /api/recommendations/{id}로 읽는다 (본인 것이 아니면 404). */
    @GetMapping("/recommendations/{id}")
    String recommendation(@PathVariable long id, Model model) {
        model.addAttribute("recommendationId", id);
        return "recommendation";
    }

    @GetMapping("/onboarding")
    String onboarding(@AuthenticationPrincipal AuthenticatedUser user, Model model) {
        if (profiles.isOnboarded(user.id())) {
            return "redirect:/profile";
        }
        model.addAttribute("mode", "onboarding");
        return "onboarding";
    }

    /** 재진단: 같은 화면에서 설문 단계만 쓴다. */
    @GetMapping("/profile/survey")
    String resurvey(@AuthenticationPrincipal AuthenticatedUser user, Model model) {
        if (!profiles.isOnboarded(user.id())) {
            return "redirect:/onboarding";
        }
        model.addAttribute("mode", "resurvey");
        // 이전 답을 JS에 미리 채우기 위해 JSON 문자열로 넘긴다
        model.addAttribute("prefillJson", json.writeValueAsString(profiles.current(user.id()).orElseThrow().answers()));
        return "onboarding";
    }

    /** 처음부터 다시: 투자 금액·성향·보유 종목을 모두 새로 입력한다. */
    @GetMapping("/profile/restart")
    String restart(@AuthenticationPrincipal AuthenticatedUser user, Model model) {
        if (!profiles.isOnboarded(user.id())) {
            return "redirect:/onboarding";
        }
        model.addAttribute("mode", "restart");
        return "onboarding";
    }

    @GetMapping("/profile")
    String profile(@AuthenticationPrincipal AuthenticatedUser user, Model model) {
        ProfileView profile = profiles.current(user.id()).orElse(null);
        if (profile == null) {
            return "redirect:/onboarding";
        }
        model.addAttribute("username", user.getUsername());
        model.addAttribute("profile", profile);
        model.addAttribute("factorLabels", Labels.FACTOR);
        model.addAttribute("methodLabels", Labels.METHOD);
        model.addAttribute("investAmount", profiles.pendingInvestAmount(user.id()).orElse(null));
        model.addAttribute("minInvestAmount", profiles.minInvestAmount());
        model.addAttribute("latestRecommendationId", recommendations.latestId(user.id()).orElse(null));
        model.addAttribute("purchased", portfolios.hasPurchased(user.id()));
        model.addAttribute("holdings", holdingRows(portfolios.holdingsSnapshot(user.id())));
        model.addAttribute("asOf", catalog.asOf());
        return "profile";
    }

    record HoldingRow(String code, String name, String sector, int quantity, Double close, Long value) {
    }

    private List<HoldingRow> holdingRows(HoldingsSnapshot snapshot) {
        return snapshot.holdings().stream().map(h -> {
            StockInfo s = catalog.find(h.code()).orElse(null);
            Double close = s == null ? null : s.lastClose();
            return new HoldingRow(h.code(), s == null ? h.code() : s.name(), s == null ? "" : s.sector(),
                    h.quantity(), close, close == null ? null : Math.round(close * h.quantity()));
        }).toList();
    }
}
