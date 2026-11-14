package kr.team2.portfolio.portfolio.internal;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface PortfolioRepository extends JpaRepository<PortfolioEntity, Long> {

    Optional<PortfolioEntity> findByUserId(long userId);
}
