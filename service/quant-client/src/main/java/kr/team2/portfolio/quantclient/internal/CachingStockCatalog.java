package kr.team2.portfolio.quantclient.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import kr.team2.portfolio.quantclient.api.QuantClient;
import kr.team2.portfolio.quantclient.api.StockCatalog;
import kr.team2.portfolio.quantclient.api.StockInfo;
import kr.team2.portfolio.quantclient.api.StockList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 엔진 전 종목 목록을 메모리에 두고 검색·검증한다 (01 FR-META-06).
 * 10분마다 다시 받고, 데이터 버전이 바뀌었으면 통째로 바꾼다.
 */
@Component
class CachingStockCatalog implements StockCatalog {

    static final Duration TTL = Duration.ofMinutes(10);

    private final QuantClient quant;
    private final Clock clock;

    private volatile Snapshot snapshot;

    @Autowired
    CachingStockCatalog(QuantClient quant) {
        this(quant, Clock.systemUTC());
    }

    CachingStockCatalog(QuantClient quant, Clock clock) {
        this.quant = quant;
        this.clock = clock;
    }

    @Override
    public Optional<StockInfo> find(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(current().byCode.get(code.trim()));
    }

    @Override
    public List<StockInfo> search(String query, String sector, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return current().list.items().stream()
                .filter(s -> sector == null || sector.isBlank() || sector.equals(s.sector()))
                .filter(s -> q.isEmpty()
                        || s.name().toLowerCase(Locale.ROOT).contains(q)
                        || s.code().startsWith(q))
                .sorted(Comparator
                        .comparing((StockInfo s) -> !s.name().equalsIgnoreCase(q))   // 정확히 같은 이름 먼저
                        .thenComparing(s -> !s.name().toLowerCase(Locale.ROOT).startsWith(q))
                        .thenComparing(StockInfo::name))
                .limit(Math.max(1, Math.min(limit, 100)))
                .toList();
    }

    @Override
    public List<String> sectors() {
        return current().sectors;
    }

    @Override
    public LocalDate asOf() {
        return current().list.asOf();
    }

    private Snapshot current() {
        Snapshot s = snapshot;
        Instant now = clock.instant();
        if (s == null || now.isAfter(s.loadedAt.plus(TTL))) {
            synchronized (this) {
                s = snapshot;
                if (s == null || now.isAfter(s.loadedAt.plus(TTL))) {
                    StockList fresh = quant.stocks();
                    s = (s != null && s.list.dataVersion() == fresh.dataVersion())
                            ? new Snapshot(s.list, s.byCode, s.sectors, now)     // 버전 같으면 시각만 갱신
                            : Snapshot.of(fresh, now);
                    snapshot = s;
                }
            }
        }
        return s;
    }

    private record Snapshot(StockList list, Map<String, StockInfo> byCode, List<String> sectors, Instant loadedAt) {

        static Snapshot of(StockList list, Instant loadedAt) {
            Map<String, StockInfo> byCode = new LinkedHashMap<>();
            list.items().forEach(s -> byCode.put(s.code(), s));
            List<String> sectors = list.items().stream().map(StockInfo::sector).distinct().sorted().toList();
            return new Snapshot(list, Map.copyOf(byCode), sectors, loadedAt);
        }
    }
}
