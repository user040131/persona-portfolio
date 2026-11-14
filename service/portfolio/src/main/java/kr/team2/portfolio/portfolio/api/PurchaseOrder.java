package kr.team2.portfolio.portfolio.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 매수 완료로 포트폴리오에 더할 내용 (docs/03 §6.4 2~5단계).
 *
 * @param buys          새로 산 종목 (수량 > 0만)
 * @param bondUnits     새로 산 채권 단위 (채권 금액 ÷ 채권 지수)
 * @param cash          새로 남긴 현금 (원, 주식 수를 맞추고 남은 돈 포함)
 * @param targetWeights 기존 보유와 합친 목표 비중. 키는 종목코드·{@code BOND}·{@code CASH}
 * @param asOf          추천 기준일
 */
public record PurchaseOrder(List<Buy> buys, double bondUnits, long cash, Map<String, Double> targetWeights,
                            LocalDate asOf) {

    /** @param price 산 가격 (기준일 종가) */
    public record Buy(String code, int shares, double price) {
    }
}
