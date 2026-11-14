package kr.team2.portfolio.portfolio.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;

/**
 * {@code holding} 테이블. code는 market 스키마 종목이라 FK가 없고 StockCatalog로 검증한 뒤 저장한다.
 */
@Entity
@Table(name = "holding")
@IdClass(HoldingEntity.Key.class)
class HoldingEntity {

    enum Origin { EXISTING, SERVICE }

    @Id
    @Column(name = "portfolio_id")
    private Long portfolioId;

    @Id
    @Column(length = 10)
    private String code;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "avg_price")
    private Double avgPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Origin origin;

    protected HoldingEntity() {
    }

    HoldingEntity(long portfolioId, String code, int quantity, Double avgPrice, Origin origin) {
        this.portfolioId = portfolioId;
        this.code = code;
        this.quantity = quantity;
        this.avgPrice = avgPrice;
        this.origin = origin;
    }

    String getCode() {
        return code;
    }

    int getQuantity() {
        return quantity;
    }

    Origin getOrigin() {
        return origin;
    }

    Double getAvgPrice() {
        return avgPrice;
    }

    /** 사용자가 직접 고침 (docs/03 §7.3 #18). */
    void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    void setAvgPrice(Double avgPrice) {
        this.avgPrice = avgPrice;
    }

    /**
     * 추가 매수 (docs/03 §6.4 2단계): 수량 q0+q1, 평균가 (q0·p0 + q1·c)/(q0+q1), origin은 그대로.
     * 온보딩 때 받은 보유는 평균가를 모르므로(null) 그대로 모른다고 둔다.
     */
    void buy(int shares, double price) {
        if (avgPrice != null) {
            avgPrice = Math.round((quantity * avgPrice + shares * price) / (quantity + shares) * 100) / 100.0;
        }
        quantity += shares;
    }

    /** 복합 키 (portfolio_id, code). */
    record Key(Long portfolioId, String code) implements Serializable {
    }
}
