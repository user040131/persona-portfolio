package kr.team2.portfolio.profile.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import kr.team2.portfolio.profile.api.Grade;

/**
 * {@code profile} 테이블 (docs/03 §4.2). 재진단마다 새 버전 행이 생기고 마지막 것만 is_current=1.
 * answers·settings는 JSON 문자열로 저장한다.
 */
@Entity
@Table(name = "profile")
class ProfileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, columnDefinition = "json")
    private String answers;

    @Column(name = "total_score", nullable = false)
    private int totalScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Grade grade;

    @Column(nullable = false, columnDefinition = "json")
    private String settings;

    @Column(name = "is_current", nullable = false)
    private boolean current;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ProfileEntity() {
    }

    ProfileEntity(long userId, int version, String answers, int totalScore, Grade grade,
                  String settings, LocalDateTime createdAt) {
        this.userId = userId;
        this.version = version;
        this.answers = answers;
        this.totalScore = totalScore;
        this.grade = grade;
        this.settings = settings;
        this.current = true;
        this.createdAt = createdAt;
    }

    void retire() {
        this.current = false;
    }

    Long getId() {
        return id;
    }

    long getUserId() {
        return userId;
    }

    int getVersion() {
        return version;
    }

    String getAnswers() {
        return answers;
    }

    int getTotalScore() {
        return totalScore;
    }

    Grade getGrade() {
        return grade;
    }

    String getSettings() {
        return settings;
    }

    LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
