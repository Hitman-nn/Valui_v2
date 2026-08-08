package com.valui.admin.dlq;

import com.valui.admin.security.ValuiPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DlqController — unit tests")
class DlqControllerTest {

    private static final ValuiPrincipal PRINCIPAL =
            new ValuiPrincipal(UUID.randomUUID(), 55086685L, "ADMIN", null);

    @Mock DlqReplayService replayService;

    @InjectMocks DlqController controller;

    // ── replay ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /replay with 5 messages → returns 200 with replayed=5")
    void replay_returnsReplayedCount() {
        given(replayService.replay()).willReturn(5L);

        ResponseEntity<Map<String, Object>> response = controller.replay(PRINCIPAL);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("replayed", 5L);
        assertThat(response.getBody()).containsEntry("status", "OK");
    }

    @Test
    @DisplayName("POST /replay with 0 messages → returns 200 with replayed=0")
    void replay_noMessages_returnsZero() {
        given(replayService.replay()).willReturn(0L);

        ResponseEntity<Map<String, Object>> response = controller.replay(PRINCIPAL);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("replayed", 0L);
    }

    @Test
    @DisplayName("POST /replay → replayService.replay() called exactly once")
    void replay_callsServiceOnce() {
        given(replayService.replay()).willReturn(0L);

        controller.replay(PRINCIPAL);

        verify(replayService, times(1)).replay();
    }

    // ── stats ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /stats → returns DlqStatsDto from service")
    void stats_returnsDtoFromService() {
        DlqStatsDto dto = new DlqStatsDto(15L, 3L, "CRITICAL");
        given(replayService.stats()).willReturn(dto);

        ResponseEntity<DlqStatsDto> response = controller.stats();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(dto);
        assertThat(response.getBody().dlqFinalCount()).isEqualTo(15L);
        assertThat(response.getBody().status()).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("GET /stats when queue is empty → status OK")
    void stats_emptyQueue_statusOk() {
        given(replayService.stats()).willReturn(new DlqStatsDto(0L, 0L, "OK"));

        ResponseEntity<DlqStatsDto> response = controller.stats();

        assertThat(response.getBody().status()).isEqualTo("OK");
        assertThat(response.getBody().dlqFinalCount()).isZero();
    }
}
