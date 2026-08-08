package com.valui.admin.parsers;

import com.valui.admin.parsers.dto.BookmakerStatusDto;
import com.valui.admin.parsers.dto.TestParseRequest;
import com.valui.admin.parsers.dto.TestParseResult;
import com.valui.common.domain.BookmakerType;
import com.valui.parser.api.BookmakerParser;
import com.valui.parser.api.ParseResult;
import com.valui.parser.factory.ParserFactory;
import com.valui.parser.health.ParserHealthService;
import com.valui.parser.util.ParsedUrlIds;
import com.valui.parser.util.UrlParser;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Tag(name = "Admin — Parsers", description = "Статус парсеров букмекеров (только ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/parsers")
@RequiredArgsConstructor
public class AdminParsersController {

    private final ParserHealthService parserHealthService;
    private final ParserFactory parserFactory;

    @GetMapping
    @Operation(summary = "Статус всех парсеров с circuit breaker метриками")
    public ResponseEntity<List<BookmakerStatusDto>> listParsers() {
        Map<BookmakerType, ParserHealthService.CircuitBreakerInfo> state =
                parserHealthService.getCurrentState();

        List<BookmakerStatusDto> result = Arrays.stream(BookmakerType.values())
                .map(bk -> {
                    ParserHealthService.CircuitBreakerInfo info = state.get(bk);
                    if (info == null) {
                        return new BookmakerStatusDto(bk.name(), "UNKNOWN", "yellow", 100f, 0, 0, 0);
                    }
                    String indicator = toIndicator(info.state(), info.successRate());
                    return new BookmakerStatusDto(
                            bk.name(),
                            info.state().name(),
                            indicator,
                            info.successRate(),
                            info.numberOfSuccessfulCalls(),
                            info.numberOfFailedCalls(),
                            info.numberOfNotPermittedCalls()
                    );
                })
                .toList();

        return ResponseEntity.ok(result);
    }

    @PostMapping("/{bookmaker}/test")
    @Operation(summary = "Тестовый парсинг URL для букмекера")
    public ResponseEntity<TestParseResult> testParse(
            @PathVariable String bookmaker,
            @Valid @RequestBody TestParseRequest req) {

        BookmakerType bk;
        try {
            bk = BookmakerType.valueOf(bookmaker.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    new TestParseResult(false, 0, List.of(), "Unknown bookmaker: " + bookmaker, 0));
        }

        long start = System.currentTimeMillis();
        try {
            BookmakerParser parser = parserFactory.getParser(bk);
            ParsedUrlIds ids = UrlParser.extractIds(req.url(), bk);

            ParseResult<?> result;
            if (ids.matchId() != null) {
                result = parser.fetchMatches(ids.tournamentId());
            } else if (ids.tournamentId() != null) {
                result = parser.fetchTournaments(ids.sportId());
            } else {
                result = parser.fetchSports();
            }

            long latency = System.currentTimeMillis() - start;
            if (!result.success()) {
                return ResponseEntity.ok(new TestParseResult(false, 0, List.of(), result.errorMessage(), latency));
            }

            List<?> items = result.data() instanceof List<?> list ? list : List.of();
            List<String> sample = items.stream().limit(5).map(Object::toString).toList();
            return ResponseEntity.ok(new TestParseResult(true, items.size(), sample, null, latency));
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("[ADMIN-PARSER] Test parse failed: bookmaker={} url={}: {}", bk, req.url(), e.getMessage(), e);
            return ResponseEntity.ok(new TestParseResult(false, 0, List.of(), e.getMessage(), latency));
        }
    }

    @PostMapping("/{bookmaker}/poll")
    @Operation(summary = "Ручной запуск poll для парсера")
    public ResponseEntity<TestParseResult> pollBookmaker(@PathVariable String bookmaker) {
        BookmakerType bk;
        try { bk = BookmakerType.valueOf(bookmaker.toUpperCase()); }
        catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                new TestParseResult(false, 0, List.of(), "Unknown bookmaker: " + bookmaker, 0));
        }
        log.info("[ADMIN-PARSER] Manual poll triggered: bookmaker={}", bk);
        long start = System.currentTimeMillis();
        try {
            BookmakerParser parser = parserFactory.getParser(bk);
            ParseResult<List<com.valui.common.parser.dto.SportDto>> result = parser.fetchSports();
            long latency = System.currentTimeMillis() - start;
            if (!result.success()) {
                log.warn("[ADMIN-PARSER] Manual poll failed: bookmaker={} latencyMs={}: {}", bk, latency, result.errorMessage());
                return ResponseEntity.ok(new TestParseResult(false, 0, List.of(), result.errorMessage(), latency));
            }
            List<?> items = result.data() != null ? result.data() : List.of();
            return ResponseEntity.ok(new TestParseResult(true, items.size(), List.of(), null, latency));
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            // Previously silent — an admin triggering a manual poll got zero server-side trace
            // of a failure, only whatever the HTTP response body happened to show.
            log.warn("[ADMIN-PARSER] Manual poll failed: bookmaker={} latencyMs={}: {}", bk, latency, e.getMessage(), e);
            return ResponseEntity.ok(new TestParseResult(false, 0, List.of(), e.getMessage(), latency));
        }
    }

    @PostMapping("/poll-all")
    @Operation(summary = "Ручной запуск poll для всех парсеров")
    public ResponseEntity<Map<String, TestParseResult>> pollAll() {
        log.info("[ADMIN-PARSER] Manual poll-all triggered for {} bookmakers", BookmakerType.values().length);
        Map<String, TestParseResult> results = new java.util.LinkedHashMap<>();
        for (BookmakerType bk : BookmakerType.values()) {
            long start = System.currentTimeMillis();
            try {
                BookmakerParser parser = parserFactory.getParser(bk);
                ParseResult<List<com.valui.common.parser.dto.SportDto>> result = parser.fetchSports();
                long latency = System.currentTimeMillis() - start;
                List<?> items = result.data() != null ? result.data() : List.of();
                if (!result.success()) {
                    log.warn("[ADMIN-PARSER] poll-all: bookmaker={} failed after {}ms: {}", bk, latency, result.errorMessage());
                }
                results.put(bk.name(), new TestParseResult(result.success(), items.size(),
                    List.of(), result.errorMessage(), latency));
            } catch (Exception e) {
                long latency = System.currentTimeMillis() - start;
                // This was the highest-impact silent failure in the admin module: an admin could
                // trigger polls for every bookmaker and get zero server-side trace of which ones
                // failed and why — only the aggregate JSON response, never logged.
                log.warn("[ADMIN-PARSER] poll-all: bookmaker={} failed after {}ms: {}", bk, latency, e.getMessage(), e);
                results.put(bk.name(), new TestParseResult(false, 0, List.of(), e.getMessage(), latency));
            }
        }
        return ResponseEntity.ok(results);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private static String toIndicator(CircuitBreaker.State state, float successRate) {
        if (state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN) return "red";
        if (state == CircuitBreaker.State.HALF_OPEN || successRate < 90f) return "yellow";
        return "green";
    }
}
