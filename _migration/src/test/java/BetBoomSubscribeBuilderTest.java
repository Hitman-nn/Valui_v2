import BotValui.Service.betboom.BetBoomSubscribeBuilder;
import org.junit.jupiter.api.Test;
import proto.betboom.Current;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BetBoomSubscribeBuilderTest {

    // === Ожидаемые Base64-строки из твоего оригинального main() ===
    private static final String EXPECTED_SPORT_ALL = "IgwKBmI1Yjc1YxICAgA=";
    private static final String EXPECTED_SPORT_TOURNAMENTS = "MhYKBmM4MTFkZBIMCgZlNzk1ZDAQAhgE";
    private static final String EXPECTED_TOURNAMENT_MATCHES = "QhcKBmU3OTVkMBINCgZjODExZGQQAhjuAg==";

    @Test
    void sportAll_generatesCorrectBase64() {
        String result = BetBoomSubscribeBuilder.sportAllBase64(
                Current.TypeLine.LINE, 0
        );
        assertEquals(EXPECTED_SPORT_ALL, result,
                "SPORT_ALL Base64 must match original");
    }

    @Test
    void sportTournaments_generatesCorrectBase64() {
        String result = BetBoomSubscribeBuilder.sportTournamentsBase64(
                Current.TypeLine.LINE, 4
        );
        assertEquals(EXPECTED_SPORT_TOURNAMENTS, result,
                "SPORT_TOURNAMENTS Base64 must match original");
    }

    @Test
    void tournamentMatches_generatesCorrectBase64() {
        String result = BetBoomSubscribeBuilder.tournamentMatchesBase64(
                Current.TypeLine.LINE, 366
        );
        assertEquals(EXPECTED_TOURNAMENT_MATCHES, result,
                "TOURNAMENT_MATCHES Base64 must match original");
    }
}