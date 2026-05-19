package com.valui.notify.vk;

import com.valui.notify.stats.NotificationStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("VkNotificationSender — unit tests")
class VkNotificationSenderTest {

    @Mock VkProperties      props;
    @Mock VkApiClient       apiClient;
    @Mock VkRateLimiter     rateLimiter;
    @Mock NotificationStats stats;
    @InjectMocks VkNotificationSender sender;

    static final long   PEER_ID       = 2000000003L;
    static final String MARKDOWN_TEXT = "🔔 *BETBOOM*\nСпартак - ЦСКА\n[https://bb.ru/1](https://bb.ru/1)\nП1: 1\\.5";
    static final String PLAIN_TEXT    = "🔔 BETBOOM\nСпартак - ЦСКА\nhttps://bb.ru/1\nП1: 1.5";

    // ── disabled ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("VK disabled → ничего не отправляется")
    void disabled_noop() {
        given(props.isEnabled()).willReturn(false);

        sender.send(PEER_ID, MARKDOWN_TEXT);

        verifyNoInteractions(rateLimiter, apiClient);
    }

    @Test
    @DisplayName("VK enabled но токен пустой → ничего не отправляется")
    void emptyToken_noop() {
        given(props.isEnabled()).willReturn(true);
        given(props.getCommunityToken()).willReturn("");

        sender.send(PEER_ID, MARKDOWN_TEXT);

        verifyNoInteractions(rateLimiter, apiClient);
    }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("rate limit: сразу разрешён → отправляется один раз")
    void rateLimitAllows_sendsOnce() throws Exception {
        given(props.isEnabled()).willReturn(true);
        given(props.getCommunityToken()).willReturn("token");
        given(rateLimiter.tryAcquire(PEER_ID)).willReturn(0L);
        given(apiClient.sendMessage(PEER_ID, PLAIN_TEXT)).willReturn(1L);

        sender.send(PEER_ID, MARKDOWN_TEXT);

        verify(apiClient).sendMessage(PEER_ID, PLAIN_TEXT);
        verify(stats).incVkSent();
    }

    @Test
    @DisplayName("API вернул ошибку → incVkSkipped")
    void apiError_incrementsSkipped() throws Exception {
        given(props.isEnabled()).willReturn(true);
        given(props.getCommunityToken()).willReturn("token");
        given(rateLimiter.tryAcquire(PEER_ID)).willReturn(0L);
        given(apiClient.sendMessage(PEER_ID, PLAIN_TEXT)).willReturn(-1L);

        sender.send(PEER_ID, MARKDOWN_TEXT);

        verify(stats).incVkSkipped();
        verify(stats, never()).incVkSent();
    }

    @Test
    @DisplayName("rate limit: burst — ждёт и отправляет после retry")
    void rateLimitBurst_retriesAndSends() throws Exception {
        given(props.isEnabled()).willReturn(true);
        given(props.getCommunityToken()).willReturn("token");
        given(rateLimiter.tryAcquire(PEER_ID))
            .willReturn(1L)   // первый вызов: подождать 1ms
            .willReturn(0L);  // повторный: разрешено
        given(apiClient.sendMessage(PEER_ID, PLAIN_TEXT)).willReturn(2L);

        sender.send(PEER_ID, MARKDOWN_TEXT);

        verify(rateLimiter, times(2)).tryAcquire(PEER_ID);
        verify(apiClient).sendMessage(PEER_ID, PLAIN_TEXT);
    }

    @Test
    @DisplayName("rate limit: исчерпан бюджет ожидания → incVkSkipped, сообщение пропускается")
    void rateLimitExhausted_skips() throws Exception {
        given(props.isEnabled()).willReturn(true);
        given(props.getCommunityToken()).willReturn("token");
        given(rateLimiter.tryAcquire(PEER_ID)).willReturn(10_001L);

        sender.send(PEER_ID, MARKDOWN_TEXT);

        verifyNoInteractions(apiClient);
        verify(stats).incVkSkipped();
    }

    // ── stripMarkdown ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("stripMarkdown: *bold* → убирает звёздочки")
    void strip_bold() {
        assertThat(VkNotificationSender.stripMarkdown("🔔 *BETBOOM*"))
            .isEqualTo("🔔 BETBOOM");
    }

    @Test
    @DisplayName("stripMarkdown: [text](url) → оставляет только url")
    void strip_link() {
        assertThat(VkNotificationSender.stripMarkdown("[https://bb.ru/1](https://bb.ru/1)"))
            .isEqualTo("https://bb.ru/1");
    }

    @Test
    @DisplayName("stripMarkdown: _italic_ → убирает подчёркивания")
    void strip_italic() {
        assertThat(VkNotificationSender.stripMarkdown("_Спартак_"))
            .isEqualTo("Спартак");
    }

    @Test
    @DisplayName("stripMarkdown: MarkdownV2 экранирование → убирает backslash")
    void strip_mdv2_escapes() {
        assertThat(VkNotificationSender.stripMarkdown("П1: 1\\.5   ТБ\\(22\\.5\\): 2\\.0"))
            .isEqualTo("П1: 1.5   ТБ(22.5): 2.0");
    }

    @Test
    @DisplayName("stripMarkdown: полное уведомление → чистый текст")
    void strip_fullNotification() {
        assertThat(VkNotificationSender.stripMarkdown(MARKDOWN_TEXT))
            .isEqualTo(PLAIN_TEXT);
    }

    @Test
    @DisplayName("stripMarkdown: null → пустая строка")
    void strip_null() {
        assertThat(VkNotificationSender.stripMarkdown(null)).isEqualTo("");
    }
}
