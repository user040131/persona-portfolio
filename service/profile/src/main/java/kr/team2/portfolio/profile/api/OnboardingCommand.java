package kr.team2.portfolio.profile.api;

import java.util.List;

/**
 * 온보딩 제출 (01 FR-ONB-02). 투자 금액·설문 답·보유 종목을 한 번에 받는다.
 *
 * @param holdings 서비스 이전부터 갖고 있던 KOSPI 종목. 없으면 빈 목록
 */
public record OnboardingCommand(long investAmount, Answers answers, List<HoldingInput> holdings) {

    public record HoldingInput(String code, int quantity) {
    }
}
