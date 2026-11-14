package kr.team2.portfolio.quantclient.api;

import java.util.List;

/**
 * 종목 하나 (엔진 I4 응답 항목, docs/03 §3).
 *
 * @param lastClose   최신 종가. 거래 가격이 없으면 null
 * @param delistRules 상장폐지 위험 규칙(D1~D5). 해당 없으면 빈 목록
 */
public record StockInfo(String code, String name, String sector, String sectorGroup,
                        boolean managed, Double lastClose, List<String> delistRules) {
}
