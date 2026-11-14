package kr.team2.portfolio.profile.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 프로필 조회 결과 (docs/03 §7.3 #7 응답).
 *
 * @param warnings 예: {@code CASH_NEED_HIGH} (A2 "30% 이상")
 */
public record ProfileView(
        long profileId,
        int version,
        int totalScore,
        Grade grade,
        String gradeLabel,
        String gradeDescription,
        ProfileSettings settings,
        Answers answers,
        List<String> warnings,
        LocalDateTime createdAt) {
}
