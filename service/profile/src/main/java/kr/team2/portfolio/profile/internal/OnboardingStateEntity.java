package kr.team2.portfolio.profile.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * {@code onboarding_state} 테이블. 행이 있으면 온보딩을 마친 사용자다.
 * pending_invest_amount는 첫 추천에 쓸 금액이고, 매수 완료 후 비운다.
 */
@Entity
@Table(name = "onboarding_state")
class OnboardingStateEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "pending_invest_amount")
    private Long pendingInvestAmount;

    @Column(name = "completed_at", nullable = false)
    private LocalDateTime completedAt;

    protected OnboardingStateEntity() {
    }

    OnboardingStateEntity(long userId, long pendingInvestAmount, LocalDateTime completedAt) {
        this.userId = userId;
        this.pendingInvestAmount = pendingInvestAmount;
        this.completedAt = completedAt;
    }

    Long getPendingInvestAmount() {
        return pendingInvestAmount;
    }

    /** 매수 완료 후: 첫 추천 금액은 다시 쓰지 않는다 (docs/03 §6.4). */
    void clearPendingInvestAmount() {
        this.pendingInvestAmount = null;
    }
}
