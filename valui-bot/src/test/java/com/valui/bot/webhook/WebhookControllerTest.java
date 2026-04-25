package com.valui.bot.webhook;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.valui.bot.config.BotMode;
import com.valui.bot.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.telegram.telegrambots.meta.bots.AbsSender;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebhookController — unit tests")
class WebhookControllerTest {

    @Mock private WebhookUpdateProcessor processor;
    @Mock private AbsSender bot;

    private MockMvc mockMvc;

    private static final String TOKEN       = "test-token-123";
    private static final String SECRET      = "my-secret";
    private static final String UPDATE_JSON = """
            {"update_id": 42, "message": {"message_id": 1, "date": 0,
             "chat": {"id": 100, "type": "private"}, "from": {"id": 100,
             "first_name": "Test", "is_bot": false}}}""";

    @BeforeEach
    void setUp() {
        BotProperties props = new BotProperties(
            TOKEN, "ValuiBot", "https://pay.valui.com",
            BotMode.WEBHOOK, "https://example.com/webhook/" + TOKEN,
            SECRET, List.of("149.154.0.0/16", "91.108.0.0/16"));

        WebhookController controller = new WebhookController(props, processor, bot);

        ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .build();
    }

    // ─── happy path ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("valid token + correct secret → 200 and update enqueued")
    void validRequest_returns200AndEnqueuesUpdate() throws Exception {
        mockMvc.perform(post("/webhook/{token}", TOKEN)
                .header("X-Telegram-Bot-Api-Secret-Token", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPDATE_JSON))
            .andExpect(status().isOk());

        then(processor).should().process(any(), any());
    }

    // ─── token validation ─────────────────────────────────────────────────────

    @Test
    @DisplayName("wrong path token → 403, update not processed")
    void wrongPathToken_returns403() throws Exception {
        mockMvc.perform(post("/webhook/wrong-token")
                .header("X-Telegram-Bot-Api-Secret-Token", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPDATE_JSON))
            .andExpect(status().isForbidden());

        then(processor).should(never()).process(any(), any());
    }

    // ─── secret token validation ──────────────────────────────────────────────

    @Test
    @DisplayName("wrong secret token → 403")
    void wrongSecretToken_returns403() throws Exception {
        mockMvc.perform(post("/webhook/{token}", TOKEN)
                .header("X-Telegram-Bot-Api-Secret-Token", "hacker")
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPDATE_JSON))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("missing secret token header → 403")
    void missingSecretToken_returns403() throws Exception {
        mockMvc.perform(post("/webhook/{token}", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPDATE_JSON))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("no secret configured → requests without header are accepted")
    void noSecretConfigured_noHeaderRequired() throws Exception {
        BotProperties noSecret = new BotProperties(
            TOKEN, "ValuiBot", "https://pay.valui.com",
            BotMode.WEBHOOK, "https://example.com/webhook/" + TOKEN,
            null, List.of("149.154.0.0/16"));

        WebhookController controller = new WebhookController(
            noSecret, processor, bot);
        ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .build();

        mvc.perform(post("/webhook/{token}", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPDATE_JSON))
            .andExpect(status().isOk());
    }
}
