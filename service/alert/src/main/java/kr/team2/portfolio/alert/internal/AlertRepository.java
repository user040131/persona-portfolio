package kr.team2.portfolio.alert.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface AlertRepository extends JpaRepository<AlertEntity, Long> {

    List<AlertEntity> findByPortfolioIdAndStatusInOrderByOpenedAtAscIdAsc(long portfolioId,
                                                                         Collection<AlertEntity.Status> statuses);

    Optional<AlertEntity> findByIdAndPortfolioId(long id, long portfolioId);
}
