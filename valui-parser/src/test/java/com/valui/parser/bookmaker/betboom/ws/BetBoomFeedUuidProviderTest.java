package com.valui.parser.bookmaker.betboom.ws;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tests the regex extraction against a real captured runtime-env.js body (24.09) via
 * applyBody() — no network call. The live fetch path (refresh()/fetchBody()) needs a real HTTP
 * round-trip to sportbook.sporthub.bet and isn't covered here.
 */
@DisplayName("BetBoomFeedUuidProvider — uuid extraction from runtime-env.js")
class BetBoomFeedUuidProviderTest {

    private final BetBoomFeedUuidProvider provider = new BetBoomFeedUuidProvider();

    @Test
    @DisplayName("getUuid() returns null before any successful fetch")
    void getUuid_nullBeforeFirstFetch() {
        assertThat(provider.getUuid()).isNull();
    }

    @Test
    @DisplayName("Extracts the uuid from a real captured runtime-env.js body")
    void applyBody_extractsUuid_fromRealCapturedBody() {
        String body = """
            const _ = {
                "BETS_HISTORY_WS_URL_TEMPLATE": "wss://{sporthubPartnerName}-ws2.sporthub.bet:443/api/bets_history_ws/v1",
                "FEED_WS_URL_TEMPLATE": "wss://{sporthubPartnerName}-ws2.sporthub.bet:443/api/tree_ws/v1?uuid=01a0d23a-f305-719c-bb3d-11c5bed388f1",
                "APP_BUILD": "8.45.2-f9e91ba9"
            };
            """;

        provider.applyBody(body);

        assertThat(provider.getUuid()).isEqualTo("01a0d23a-f305-719c-bb3d-11c5bed388f1");
    }

    @Test
    @DisplayName("Keeps the previous value when FEED_WS_URL_TEMPLATE is absent from a later fetch")
    void applyBody_keepsPrevious_whenTemplateMissingLater() {
        provider.applyBody("\"FEED_WS_URL_TEMPLATE\":\"wss://x-ws2.sporthub.bet:443/api/tree_ws/v1?uuid=deadbeef-dead-beef-dead-beefdeadbeef\"");
        assertThat(provider.getUuid()).isEqualTo("deadbeef-dead-beef-dead-beefdeadbeef");

        provider.applyBody("const _ = { \"APP_BUILD\": \"9.0.0-abcdef12\" };");

        assertThat(provider.getUuid()).isEqualTo("deadbeef-dead-beef-dead-beefdeadbeef");
    }

    @Test
    @DisplayName("A later fetch with a different uuid rotates the cached value (widget redeploy)")
    void applyBody_rotatesUuid_onLaterDifferentValue() {
        provider.applyBody("\"FEED_WS_URL_TEMPLATE\":\"wss://x-ws2.sporthub.bet:443/api/tree_ws/v1?uuid=11111111-1111-1111-1111-111111111111\"");
        provider.applyBody("\"FEED_WS_URL_TEMPLATE\":\"wss://x-ws2.sporthub.bet:443/api/tree_ws/v1?uuid=22222222-2222-2222-2222-222222222222\"");

        assertThat(provider.getUuid()).isEqualTo("22222222-2222-2222-2222-222222222222");
    }
}
