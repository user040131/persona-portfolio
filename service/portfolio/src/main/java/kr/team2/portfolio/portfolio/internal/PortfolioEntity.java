package kr.team2.portfolio.portfolio.internal;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** {@code portfolio} 테이블 (docs/03 §4.2). 사용자당 하나. */
@Entity
@Table(name = "portfolio")
class PortfolioEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private long userId;

    @Column(name = "bond_units", nullable = false)
    private double bondUnits;

    @Column(nullable = false)
    private long cash;

    @Column(name = "purchased_at")
    private LocalDate purchasedAt;

    @Column(name = "holdings_changed_at", nullable = false)
    private LocalDate holdingsChangedAt;

    @Column(name = "last_r6_year")
    private Short lastR6Year;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Version
    @Column(nullable = false)
    private long version;

    // 보유 행이 portfolio_id를 직접 갖고 있으므로(복합 키 일부) 조인 열은 읽기 전용으로 둔다
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "portfolio_id", insertable = false, updatable = false)
    @OrderBy("code")
    private List<HoldingEntity> holdings = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "portfolio_id", insertable = false, updatable = false)
    @OrderBy("asset")
    private List<TargetWeightEntity> targetWeights = new ArrayList<>();

    protected PortfolioEntity() {
    }

    PortfolioEntity(long userId, LocalDate holdingsChangedAt, LocalDateTime createdAt) {
        this.userId = userId;
        this.holdingsChangedAt = holdingsChangedAt;
        this.createdAt = createdAt;
    }

    Long getId() {
        return id;
    }

    long getUserId() {
        return userId;
    }

    double getBondUnits() {
        return bondUnits;
    }

    long getCash() {
        return cash;
    }

    List<HoldingEntity> getHoldings() {
        return holdings;
    }

    List<TargetWeightEntity> getTargetWeights() {
        return targetWeights;
    }

    LocalDate getPurchasedAt() {
        return purchasedAt;
    }

    LocalDate getHoldingsChangedAt() {
        return holdingsChangedAt;
    }

    Short getLastR6Year() {
        return lastR6Year;
    }

    void setBondUnits(double bondUnits) {
        this.bondUnits = bondUnits;
    }

    void setCash(long cash) {
        this.cash = cash;
    }

    void setLastR6Year(short year) {
        this.lastR6Year = year;
    }

    void setHoldingsChangedAt(LocalDate holdingsChangedAt) {
        this.holdingsChangedAt = holdingsChangedAt;
    }

    /** 매수 완료: 채권·현금을 더하고 기준일을 옮긴다 (docs/03 §6.4 3·5단계). */
    void addPurchase(double bondUnits, long cash, LocalDate asOf) {
        this.bondUnits += bondUnits;
        this.cash += cash;
        this.purchasedAt = asOf;
        this.holdingsChangedAt = asOf;
    }
}
