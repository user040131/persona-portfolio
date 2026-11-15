package kr.team2.portfolio.profile.internal;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.team2.portfolio.common.BusinessException;
import kr.team2.portfolio.common.ErrorCode;
import kr.team2.portfolio.profile.api.Answers;
import kr.team2.portfolio.profile.api.OnboardingCommand;
import kr.team2.portfolio.profile.api.OnboardingCompleted;
import kr.team2.portfolio.profile.api.ProfileChanged;
import kr.team2.portfolio.profile.api.ProfileFacade;
import kr.team2.portfolio.profile.api.ProfileSettings;
import kr.team2.portfolio.profile.api.ProfileView;
import kr.team2.portfolio.profile.api.Survey;
import kr.team2.portfolio.quantclient.api.StockCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 온보딩·재진단·프로필 조회 (docs/03 §6.1, 01 FR-ONB).
 * 온보딩 이벤트는 같은 트랜잭션 안에서 처리되므로(portfolio 모듈이 동기 리스너),
 * 포트폴리오 생성이 실패하면 온보딩도 함께 취소된다.
 */
@Service
@EnableConfigurationProperties({Survey.class, ProfileMappingProperties.class})
class ProfileService implements ProfileFacade {

    static final String CASH_NEED_HIGH = "CASH_NEED_HIGH";
    private static final int A2_OVER_30_PCT = 4;

    private final Survey survey;
    private final ProfileMappingProperties props;
    private final ProfileCalculator calculator;
    private final ProfileRepository profiles;
    private final OnboardingStateRepository onboarding;
    private final StockCatalog catalog;
    private final ApplicationEventPublisher events;
    private final JsonMapper json;
    private final Clock clock;

    @Autowired
    ProfileService(Survey survey, ProfileMappingProperties props, ProfileRepository profiles,
                   OnboardingStateRepository onboarding, StockCatalog catalog,
                   ApplicationEventPublisher events, JsonMapper json) {
        this(survey, props, profiles, onboarding, catalog, events, json, Clock.systemUTC());
    }

    ProfileService(Survey survey, ProfileMappingProperties props, ProfileRepository profiles,
                   OnboardingStateRepository onboarding, StockCatalog catalog,
                   ApplicationEventPublisher events, JsonMapper json, Clock clock) {
        this.survey = survey;
        this.props = props;
        this.calculator = new ProfileCalculator(survey, props.profile());
        this.profiles = profiles;
        this.onboarding = onboarding;
        this.catalog = catalog;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Survey survey() {
        return survey;
    }

    @Override
    public long minInvestAmount() {
        return props.minInvestAmount();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> pendingInvestAmount(long userId) {
        return onboarding.findById(userId).map(OnboardingStateEntity::getPendingInvestAmount);
    }

    @Override
    @Transactional
    public ProfileView onboard(long userId, OnboardingCommand cmd) {
        if (onboarding.existsById(userId)) {
            throw new BusinessException(ErrorCode.ALREADY_ONBOARDED);
        }
        checkAmount(cmd.investAmount());
        List<OnboardingCompleted.ExistingHolding> holdings = validateHoldings(cmd.holdings());
        ProfileEntity saved = save(userId, 1, cmd.answers());
        onboarding.save(new OnboardingStateEntity(userId, cmd.investAmount(), LocalDateTime.now(clock)));
        events.publishEvent(new OnboardingCompleted(userId, saved.getId(), holdings));
        return view(saved);
    }

    @Override
    @Transactional
    public ProfileView restart(long userId, OnboardingCommand cmd) {
        OnboardingStateEntity state = onboarding.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROFILE_NOT_FOUND));
        checkAmount(cmd.investAmount());
        List<OnboardingCompleted.ExistingHolding> holdings = validateHoldings(cmd.holdings());
        ProfileEntity saved = renew(userId, cmd.answers());
        state.restart(cmd.investAmount(), LocalDateTime.now(clock));
        events.publishEvent(new OnboardingCompleted(userId, saved.getId(), holdings));
        return view(saved);
    }

    @Override
    @Transactional
    public ProfileView resurvey(long userId, Answers answers) {
        ProfileEntity saved = renew(userId, answers);
        events.publishEvent(new ProfileChanged(userId, saved.getId()));
        return view(saved);
    }

    /** 지금 프로필을 내리고 다음 버전으로 저장한다. */
    private ProfileEntity renew(long userId, Answers answers) {
        ProfileEntity old = profiles.findByUserIdAndCurrentTrue(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROFILE_NOT_FOUND));
        old.retire();
        profiles.saveAndFlush(old);
        return save(userId, old.getVersion() + 1, answers);
    }

    private void checkAmount(long amount) {
        if (amount < props.minInvestAmount()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    Map.of("field", "investAmount", "min", props.minInvestAmount()));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProfileView> current(long userId) {
        return profiles.findByUserIdAndCurrentTrue(userId).map(this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProfileView> find(long userId, long profileId) {
        return profiles.findByIdAndUserId(profileId, userId).map(this::view);
    }

    @Override
    @Transactional
    public void clearPendingInvestAmount(long userId) {
        onboarding.findById(userId).ifPresent(OnboardingStateEntity::clearPendingInvestAmount);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isOnboarded(long userId) {
        return onboarding.existsById(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProfileSettings> currentSettings(long userId) {
        return profiles.findByUserIdAndCurrentTrue(userId)
                .map(p -> json.readValue(p.getSettings(), ProfileSettings.class));
    }

    @Override
    public ProfileSettings calculate(Answers answers) {
        return calculator.settings(answers, catalog.sectors());
    }

    private ProfileEntity save(long userId, int version, Answers answers) {
        ProfileSettings settings = calculator.settings(answers, catalog.sectors());
        int score = calculator.totalScore(answers.q());
        return profiles.saveAndFlush(new ProfileEntity(userId, version,
                json.writeValueAsString(answers), score, settings.grade(),
                json.writeValueAsString(settings), LocalDateTime.now(clock)));
    }

    /** 수량 ≥ 1, 있는 종목만, 같은 코드는 합친다 (01 FR-ONB-08). */
    private List<OnboardingCompleted.ExistingHolding> validateHoldings(List<OnboardingCommand.HoldingInput> input) {
        if (input == null) {
            return List.of();
        }
        Map<String, Integer> merged = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        for (OnboardingCommand.HoldingInput h : input) {
            String code = h.code() == null ? "" : h.code().trim();
            if (h.quantity() < 1) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of("field", "holdings.quantity", "code", code));
            }
            if (catalog.find(code).isEmpty()) {
                unknown.add(code);
                continue;
            }
            merged.merge(code, h.quantity(), Integer::sum);
        }
        if (!unknown.isEmpty()) {
            throw new BusinessException(ErrorCode.UNKNOWN_STOCK, Map.of("codes", unknown));
        }
        return merged.entrySet().stream()
                .map(e -> new OnboardingCompleted.ExistingHolding(e.getKey(), e.getValue()))
                .toList();
    }

    private ProfileView view(ProfileEntity p) {
        Answers answers = json.readValue(p.getAnswers(), Answers.class);
        ProfileSettings settings = json.readValue(p.getSettings(), ProfileSettings.class);
        List<String> warnings = answers.a2() == A2_OVER_30_PCT ? List.of(CASH_NEED_HIGH) : List.of();
        return new ProfileView(p.getId(), p.getVersion(), p.getTotalScore(), p.getGrade(),
                survey.label(p.getGrade()), survey.description(p.getGrade()),
                settings, answers, warnings, p.getCreatedAt());
    }
}
