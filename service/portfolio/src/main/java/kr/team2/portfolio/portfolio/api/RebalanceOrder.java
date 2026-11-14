package kr.team2.portfolio.portfolio.api;

import java.time.LocalDate;
import java.util.Map;

/**
 * 리밸런싱 제안대로 했다고 기록할 내용 (엔진 I9 결과의 {@code after}). 수량·채권·현금은 거래 후의 값 그대로다.
 *
 * @param quantities    거래 후 종목별 수량. 여기에 없는 종목은 모두 판 것이다
 * @param buyPrices     새로 산 종목의 가격 (평균가 계산용, 기준일 종가)
 * @param targetWeights 새 목표 비중 (R1·R2·R7은 깎은 비중, R6은 새로 짠 비중, 그 밖은 기존 목표)
 */
public record RebalanceOrder(Map<String, Integer> quantities, Map<String, Double> buyPrices, double bondUnits,
                             long cash, Map<String, Double> targetWeights, LocalDate asOf) {
}
