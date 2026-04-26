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
    void fetchSports_wsTimeout_returnsError() throws Exception {
        when(wsService.sendAndAwaitFiltered(any(), anyLong(), any())).thenReturn(null);
        ParseResult<List<SportDto>> result = parser.fetchSports();
        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("WS timeout");
    }

    @Test
    void fetchSports_parsesResponse() throws Exception {
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
    void fetchTournaments_invalidSportId_returnsError() {
        ParseResult<List<TournamentDto>> result = parser.fetchTournaments("not-a-number");
        assertThat(result.success()).isFalse();
    }

    @Test
    void fetchMatches_invalidTournamentId_returnsError() {
        ParseResult<List<MatchDto>> result = parser.fetchMatches("abc");
        assertThat(result.success()).isFalse();
    }

    @Test
    void fetchMatches_wsTimeout_returnsError() throws Exception {
        when(wsService.sendAndAwaitFiltered(any(), anyLong(), any())).thenReturn(null);
        ParseResult<List<MatchDto>> result = parser.fetchMatches("12345");
        assertThat(result.success()).isFalse();
    }

    // ── proto helpers ─────────────────────────────────────────────────────────

    private static byte[] buildSportAllResponse() throws Exception {
        Sport sport = Sport.newBuilder().setId(2).setName("Football").setAlias("football").build();
        SportAllBody.Row row = SportAllBody.Row.newBuilder().setSport(sport).build();
        SportAllBody body = SportAllBody.newBuilder().addRows(row).build();

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
