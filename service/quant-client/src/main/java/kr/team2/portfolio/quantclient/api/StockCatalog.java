package kr.team2.portfolio.quantclient.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 종목 목록 캐시 (01 FR-META-06). 엔진 I4를 한 번 받아 두고 검색·검증은 메모리에서 한다.
 */
public interface StockCatalog {

    Optional<StockInfo> find(String code);

    /** 이름 부분일치 또는 코드 앞자리 일치. 이름이 정확히 같은 종목이 먼저 나온다. */
    List<StockInfo> search(String query, String sector, int limit);

    /** 업종명 목록 (가나다순, A7 선택지). */
    List<String> sectors();

    LocalDate asOf();
}
