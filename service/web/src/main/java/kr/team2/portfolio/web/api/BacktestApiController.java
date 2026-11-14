package kr.team2.portfolio.web.api;

import java.nio.charset.StandardCharsets;
import java.util.List;
import kr.team2.portfolio.auth.api.AuthenticatedUser;
import kr.team2.portfolio.backtest.api.BacktestFacade;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 백테스트 API (docs/03 §7.2 #22~25). 결과는 엔진 응답(snake_case)을 그대로 전달한다 (§6.6). */
@RestController
@RequestMapping("/api/backtests")
class BacktestApiController {

    private final BacktestFacade backtests;

    BacktestApiController(BacktestFacade backtests) {
        this.backtests = backtests;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    BacktestFacade.JobView create(@AuthenticationPrincipal AuthenticatedUser user,
                                  @RequestBody(required = false) BacktestFacade.CreateCommand req) {
        return backtests.create(user.id(), req == null ? new BacktestFacade.CreateCommand(null, null, null, null) : req);
    }

    @GetMapping
    List<BacktestFacade.JobView> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return backtests.list(user.id());
    }

    @GetMapping("/{jobId}")
    ResponseEntity<String> status(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String jobId) {
        return json(backtests.status(user.id(), jobId));
    }

    @GetMapping("/{jobId}/nav")
    ResponseEntity<String> nav(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String jobId,
                               @RequestParam(required = false) String strategies,
                               @RequestParam(defaultValue = "D") String freq) {
        return json(backtests.nav(user.id(), jobId, strategies, freq));
    }

    @GetMapping("/{jobId}/holdings")
    ResponseEntity<String> holdings(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String jobId,
                                    @RequestParam String strategy, @RequestParam(required = false) String date) {
        return json(backtests.holdings(user.id(), jobId, strategy, date));
    }

    @GetMapping("/{jobId}/export.csv")
    ResponseEntity<byte[]> csv(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String jobId) {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};   // 엑셀에서 한글·UTF-8로 열리게
        byte[] body = backtests.csv(user.id(), jobId).getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(body, 0, out, bom.length, body.length);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"backtest-" + jobId + ".csv\"")
                .body(out);
    }

    static ResponseEntity<String> json(String raw) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(raw);
    }
}
