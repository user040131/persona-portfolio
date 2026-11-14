package kr.team2.portfolio.backtest.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import kr.team2.portfolio.backtest.api.BacktestStatus;

/** {@code backtest_job} 테이블: 누가 어떤 엔진 작업을 만들었는지 (docs/03 §4.2). 결과는 엔진(market 스키마)에 있다. */
@Entity
@Table(name = "backtest_job")
class BacktestJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "job_id", nullable = false, unique = true, length = 36, columnDefinition = "char(36)")
    private String jobId;

    @Column(nullable = false, columnDefinition = "json")
    private String params;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_status", nullable = false, length = 10)
    private BacktestStatus lastStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected BacktestJobEntity() {
    }

    BacktestJobEntity(long userId, String jobId, String params, LocalDateTime createdAt) {
        this.userId = userId;
        this.jobId = jobId;
        this.params = params;
        this.lastStatus = BacktestStatus.QUEUED;
        this.createdAt = createdAt;
    }

    String getJobId() {
        return jobId;
    }

    String getParams() {
        return params;
    }

    BacktestStatus getLastStatus() {
        return lastStatus;
    }

    LocalDateTime getCreatedAt() {
        return createdAt;
    }

    void setLastStatus(BacktestStatus lastStatus) {
        this.lastStatus = lastStatus;
    }
}
