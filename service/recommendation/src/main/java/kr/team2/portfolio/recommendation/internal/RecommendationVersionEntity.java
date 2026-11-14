package kr.team2.portfolio.recommendation.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * {@code recommendation_version} 테이블. result는 엔진 I6 응답 본문 그대로(snake_case),
 * edits는 그 버전까지 누적된 수정({removedCodes, safeRatio, method})이다.
 */
@Entity
@Table(name = "recommendation_version")
@IdClass(RecommendationVersionEntity.Key.class)
class RecommendationVersionEntity {

    @Id
    @Column(name = "recommendation_id")
    private Long recommendationId;

    @Id
    @Column(name = "version")
    private Integer version;

    @Column(nullable = false, columnDefinition = "json")
    private String edits;

    @Column(nullable = false, columnDefinition = "json")
    private String result;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected RecommendationVersionEntity() {
    }

    RecommendationVersionEntity(long recommendationId, int version, String edits, String result,
                                LocalDateTime createdAt) {
        this.recommendationId = recommendationId;
        this.version = version;
        this.edits = edits;
        this.result = result;
        this.createdAt = createdAt;
    }

    int getVersion() {
        return version;
    }

    String getEdits() {
        return edits;
    }

    String getResult() {
        return result;
    }

    record Key(Long recommendationId, Integer version) implements Serializable {
    }
}
