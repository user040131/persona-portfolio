package kr.team2.portfolio.backtest.internal;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface BacktestJobRepository extends JpaRepository<BacktestJobEntity, Long> {

    Optional<BacktestJobEntity> findByJobIdAndUserId(String jobId, long userId);

    List<BacktestJobEntity> findByUserIdOrderByCreatedAtDescIdDesc(long userId);
}
