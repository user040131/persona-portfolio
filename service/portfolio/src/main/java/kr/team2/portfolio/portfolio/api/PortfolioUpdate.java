package kr.team2.portfolio.portfolio.api;

import java.util.List;

/**
 * 보유 직접 수정 (docs/03 §7.3 #18, 01 FR-PF-04). 보낸 것만 바뀌고, 비운 값은 그대로다.
 *
 * @param holdings   고칠 종목. 수량 0이면 삭제, 없던 종목이면 추가
 * @param bondAmount 채권 평가금액(원). 엔진의 채권 지수로 단위로 바꿔 저장한다
 * @param cash       현금(원)
 */
public record PortfolioUpdate(List<HoldingEdit> holdings, Long bondAmount, Long cash) {

    /** @param avgPrice 평균 매입가(원). 비우면 그대로 */
    public record HoldingEdit(String code, int quantity, Double avgPrice) {
    }
}
