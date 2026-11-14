package kr.team2.portfolio.portfolio.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDateTime;

/** {@code target_weight} 테이블. 매수 완료 때 통째로 바뀐다. asset은 종목코드·BOND·CASH. */
@Entity
@Table(name = "target_weight")
@IdClass(TargetWeightEntity.Key.class)
class TargetWeightEntity {

    @Id
    @Column(name = "portfolio_id")
    private Long portfolioId;

    @Id
    @Column(length = 10)
    private String asset;

    @Column(nullable = false)
    private double weight;

    @Column(name = "set_at", nullable = false)
    private LocalDateTime setAt;

    protected TargetWeightEntity() {
    }

    TargetWeightEntity(long portfolioId, String asset, double weight, LocalDateTime setAt) {
        this.portfolioId = portfolioId;
        this.asset = asset;
        this.weight = weight;
        this.setAt = setAt;
    }

    String getAsset() {
        return asset;
    }

    double getWeight() {
        return weight;
    }

    void update(double weight, LocalDateTime setAt) {
        this.weight = weight;
        this.setAt = setAt;
    }

    record Key(Long portfolioId, String asset) implements Serializable {
    }
}
