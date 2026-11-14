package kr.team2.portfolio.recommendation.api;

/**
 * "이 종목은 왜 없나요?" 응답 (docs/03 §7.3 #13).
 *
 * @param reason 탈락 사유 코드. 추천에 들어 있으면 null
 */
public record WhyNot(String code, String name, boolean inPortfolio, String reason, String text) {
}
