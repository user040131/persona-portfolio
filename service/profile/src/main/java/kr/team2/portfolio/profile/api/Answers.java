package kr.team2.portfolio.profile.api;

import java.util.List;

/**
 * 온보딩 설문 답 (docs/03 §6.1).
 *
 * @param q  Q1~Q7 선택지 번호(1~5), 7개
 * @param a2 1 없음 / 2 10% 이하 / 3 10~30% / 4 30% 이상
 * @param a3 비상금 있음
 * @param a4 1 20% 이하 / 2 20~50% / 3 50~80% / 4 80% 이상
 * @param a5 1 10개 / 2 15개 / 3 20개 이상 / 4 상관없음
 * @param a6 Factor 이름들 또는 ["ANY"]
 * @param a7 제외 업종명
 */
public record Answers(List<Integer> q, int a2, boolean a3, int a4, int a5, List<String> a6, List<String> a7) {
}
