package kr.team2.portfolio.recommendation.internal;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface RecommendationRepository extends JpaRepository<RecommendationEntity, Long> {

    Optional<RecommendationEntity> findByIdAndUserId(long id, long userId);

    Optional<RecommendationEntity> findFirstByUserIdOrderByCreatedAtDescIdDesc(long userId);
}

interface RecommendationVersionRepository
        extends JpaRepository<RecommendationVersionEntity, RecommendationVersionEntity.Key> {
}
