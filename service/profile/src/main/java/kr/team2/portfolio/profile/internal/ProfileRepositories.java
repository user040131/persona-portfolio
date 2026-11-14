package kr.team2.portfolio.profile.internal;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProfileRepository extends JpaRepository<ProfileEntity, Long> {

    Optional<ProfileEntity> findByUserIdAndCurrentTrue(long userId);

    Optional<ProfileEntity> findByIdAndUserId(long id, long userId);
}

interface OnboardingStateRepository extends JpaRepository<OnboardingStateEntity, Long> {
}
