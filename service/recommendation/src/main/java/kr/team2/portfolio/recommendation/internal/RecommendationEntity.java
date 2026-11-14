package kr.team2.portfolio.recommendation.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** {@code recommendation} 테이블 (docs/03 §4.2). 수정할 때마다 버전이 늘고 latest_version이 따라간다. */
@Entity
@Table(name = "recommendation")
class RecommendationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "profile_id", nullable = false)
    private long profileId;

    @Column(name = "data_version", nullable = false)
    private long dataVersion;

    @Column(name = "as_of", nullable = false)
    private LocalDate asOf;

    @Column(name = "invest_amount", nullable = false)
    private long investAmount;

    @Column(name = "latest_version", nullable = false)
    private int latestVersion;

    @Column(name = "purchased_version")
    private Integer purchasedVersion;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected RecommendationEntity() {
    }

    RecommendationEntity(long userId, long profileId, long dataVersion, LocalDate asOf, long investAmount,
                         LocalDateTime createdAt) {
        this.userId = userId;
        this.profileId = profileId;
        this.dataVersion = dataVersion;
        this.asOf = asOf;
        this.investAmount = investAmount;
        this.latestVersion = 1;
        this.createdAt = createdAt;
    }

    Long getId() {
        return id;
    }

    long getUserId() {
        return userId;
    }

    long getProfileId() {
        return profileId;
    }

    long getInvestAmount() {
        return investAmount;
    }

    LocalDate getAsOf() {
        return asOf;
    }

    int getLatestVersion() {
        return latestVersion;
    }

    Integer getPurchasedVersion() {
        return purchasedVersion;
    }

    LocalDateTime getCreatedAt() {
        return createdAt;
    }

    boolean isPurchased() {
        return purchasedVersion != null;
    }

    /** 새 버전이 저장됐다. 기준일·데이터 버전은 새 계산 결과를 따른다. */
    void advance(long dataVersion, LocalDate asOf) {
        this.latestVersion += 1;
        this.dataVersion = dataVersion;
        this.asOf = asOf;
    }

    void markPurchased(int version) {
        this.purchasedVersion = version;
    }
}
