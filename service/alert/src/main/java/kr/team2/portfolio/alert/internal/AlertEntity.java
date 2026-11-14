package kr.team2.portfolio.alert.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import kr.team2.portfolio.alert.api.AlertType;

/**
 * {@code alert} 테이블 (docs/03 §4.2). 같은 (포트폴리오, 종류, 대상)의 열린 알림(OPEN·DISMISSED)은
 * DB의 계산 열 active_key 유일 제약으로 하나만 있다. active_key는 DB가 만들므로 매핑하지 않는다.
 */
@Entity
@Table(name = "alert")
class AlertEntity {

    enum Status { OPEN, DISMISSED, CLOSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "portfolio_id", nullable = false)
    private long portfolioId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2, columnDefinition = "char(2)")
    private AlertType type;

    @Column(nullable = false, length = 50)
    private String target;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(nullable = false, columnDefinition = "json")
    private String detail;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    protected AlertEntity() {
    }

    AlertEntity(long portfolioId, AlertType type, String target, String detail, LocalDateTime openedAt) {
        this.portfolioId = portfolioId;
        this.type = type;
        this.target = target;
        this.status = Status.OPEN;
        this.detail = detail;
        this.openedAt = openedAt;
    }

    Long getId() {
        return id;
    }

    AlertType getType() {
        return type;
    }

    String getTarget() {
        return target;
    }

    Status getStatus() {
        return status;
    }

    String getDetail() {
        return detail;
    }

    LocalDateTime getOpenedAt() {
        return openedAt;
    }

    /** (종류, 대상) — 같은 조건인지 비교할 때 쓴다. */
    String key() {
        return type + ":" + target;
    }

    boolean isActive() {
        return status != Status.CLOSED;
    }

    void updateDetail(String detail) {
        this.detail = detail;
    }

    void dismiss() {
        this.status = Status.DISMISSED;
    }

    void close(LocalDateTime at) {
        this.status = Status.CLOSED;
        this.closedAt = at;
    }
}
