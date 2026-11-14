package kr.team2.portfolio.quantclient.api;

import java.time.LocalDate;
import java.util.List;

/** 엔진 I4 응답: 전 종목 목록과 기준일·데이터 버전. */
public record StockList(LocalDate asOf, long dataVersion, List<StockInfo> items) {
}
