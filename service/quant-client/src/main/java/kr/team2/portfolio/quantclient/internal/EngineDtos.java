package kr.team2.portfolio.quantclient.internal;

import java.time.LocalDate;
import java.util.List;
import kr.team2.portfolio.quantclient.api.StockInfo;
import kr.team2.portfolio.quantclient.api.StockList;

/**
 * 엔진 응답 원문 (snake_case, docs/03 §2.1). 모듈 밖으로는 api 타입으로 바꿔서 내보낸다.
 */
final class EngineDtos {

    private EngineDtos() {
    }

    record HealthResponse(String status, boolean data_loaded) {
    }

    record StocksResponse(LocalDate as_of, long data_version, List<StockItem> items) {

        StockList toApi() {
            return new StockList(as_of, data_version, items.stream().map(StockItem::toApi).toList());
        }
    }

    record StockItem(String code, String name, String sector, String sector_group, boolean is_managed,
                     Double last_close, List<String> delist_rules) {

        StockInfo toApi() {
            return new StockInfo(code, name, sector, sector_group, is_managed, last_close,
                    delist_rules == null ? List.of() : List.copyOf(delist_rules));
        }
    }
}
