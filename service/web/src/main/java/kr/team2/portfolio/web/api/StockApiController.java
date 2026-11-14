package kr.team2.portfolio.web.api;

import java.util.List;
import kr.team2.portfolio.common.BusinessException;
import kr.team2.portfolio.common.ErrorCode;
import kr.team2.portfolio.quantclient.api.StockCatalog;
import kr.team2.portfolio.quantclient.api.StockInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 종목 검색·상세·업종 목록 (docs/03 §7.2 #3~5). 엔진을 부르지 않고 캐시에서 찾는다. */
@RestController
@RequestMapping("/api")
class StockApiController {

    private final StockCatalog catalog;

    StockApiController(StockCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/stocks")
    List<StockInfo> search(@RequestParam(defaultValue = "") String q,
                           @RequestParam(required = false) String sector,
                           @RequestParam(defaultValue = "20") int limit) {
        return catalog.search(q, sector, limit);
    }

    @GetMapping("/stocks/{code}")
    StockInfo stock(@PathVariable String code) {
        return catalog.find(code).orElseThrow(() -> new BusinessException(ErrorCode.STOCK_NOT_FOUND));
    }

    @GetMapping("/sectors")
    List<String> sectors() {
        return catalog.sectors();
    }
}
