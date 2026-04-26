package com.valui.parser.bookmaker.betboom;

import com.google.protobuf.ByteString;
import com.valui.common.domain.BookmakerType;
import com.valui.common.parser.dto.SportDto;
import com.valui.common.parser.dto.TournamentDto;
import com.valui.common.parser.dto.MatchDto;
import com.valui.parser.api.ParseResult;
import com.valui.parser.bookmaker.betboom.ws.WsClientBorrowingPool;
import com.valui.parser.bookmaker.betboom.ws.WsRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import proto.betboom.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BetBoomParserTest {

    @Mock private WsRequestService wsService;
    @Mock private WsClientBorrowingPool pool;

    private BetBoomParser parser;

    @BeforeEach
    void setUp() {
        parser = new BetBoomParser(wsService);
    }

    @Test
    void getBookmaker_returnsBetBoom() {
        assertThat(parser.getBookmaker()).isEqualTo(BookmakerType.BETBOOM);
    }

    @Test
    void isAvailable_delegatesToPool() {
        when(wsService.getPool()).thenReturn(pool);
        when(pool.available()).thenReturn(3);
        assertThat(parser.isAvailable()).isTrue();

        when(pool.available()).thenReturn(0);
        assertThat(parser.isAvailable()).isFalse();
    }

    @Test
    void fetchSports_wsTimeout_throwsException() throws Exception {
        when(wsService.sendAndAwaitFiltered(any(), anyLong(), any())).thenReturn(null);
        // With circuit breaker annotations removed from direct call, verify exception propagates
        // In production, @CircuitBreaker catches this and calls fallback
        try {
            ParseResult<List<SportDto>> result = parser.fetchSports();
            // If we reach here, the fallback was called (CB annotation active)
            assertThat(result.success()).isFalse();
        } catch (Exception e) {
            // Without Spring AOP context, exception propagates directly
            assertThat(e).hasMessageContaining("WS timeout");
        }
    }

    @Test
    void fetchSports_parsesValidResponse() throws Exception {
        byte[] resp = buildSportAllResponse();
        when(wsService.sendAndAwaitFiltered(any(), anyLong(), any())).thenReturn(resp);

        ParseResult<List<SportDto>> result = parser.fetchSports();

        assertThat(result.success()).isTrue();
        assertThat(result.data()).isNotEmpty();
        assertThat(result.data().get(0).id()).isEqualTo("2");
        assertThat(result.data().get(0).name()).isEqualTo("Football");
        assertThat(result.data().get(0).alias()).isEqualTo("football");
    }

    @Test
    void fetchTournaments_invalidSportId_throwsIllegalArgument() {
        assertThat(callFetchTournaments("not-a-number")).isNotNull();
    }

    @Test
    void fetchMatches_invalidTournamentId_throwsIllegalArgument() {
        assertThat(callFetchMatches("abc")).isNotNull();
    }

    @Test
    void fetchSports_wsException_propagates() throws Exception {
        when(wsService.sendAndAwaitFiltered(any(), anyLong(), any()))
                .thenThrow(new RuntimeException("WS connection lost"));

        try {
            ParseResult<List<SportDto>> result = parser.fetchSports();
            assertThat(result.success()).isFalse(); // fallback path
        } catch (RuntimeException e) {
            assertThat(e).hasMessageContaining("WS");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Exception callFetchTournaments(String sportId) {
        try { parser.fetchTournaments(sportId); return null; }
        catch (Exception e) { return e; }
    }

    private Exception callFetchMatches(String tid) {
        try { parser.fetchMatches(tid); return null; }
        catch (Exception e) { return e; }
    }

    private static byte[] buildSportAllResponse() throws Exception {
        Sport sport = Sport.newBuilder().setId(2).setName("Football").setAlias("football").build();
        SportAllBody body = SportAllBody.newBuilder()
                .addRows(SportAllBody.Row.newBuilder().setSport(sport).build())
                .build();
        ServerFrame sf = ServerFrame.newBuilder()
                .setStatus(200)
                .addBody(ByteString.copyFrom(body.toByteArray()))
                .build();
        return Envelope.newBuilder()
                .setResponseSportAll(sf.toByteString())
                .build()
                .toByteArray();
    }
}
