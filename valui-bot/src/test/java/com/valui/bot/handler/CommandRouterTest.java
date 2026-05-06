package com.valui.bot.handler;

import com.valui.betting.service.ChatMemberService;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import com.valui.bot.state.UserBotSession;
import com.valui.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.bots.AbsSender;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CommandRouter — unit tests")
class CommandRouterTest {

    @Mock private BotSessionService  sessionService;
    @Mock private UserService        userService;
    @Mock private ChatMemberService  chatMemberService;
    @Mock private AbsSender          sender;

    private static final Long CHAT_ID = 100L;

    @BeforeEach
    void setUp() {
        given(sessionService.getSession(CHAT_ID)).willReturn(idleSession());
        given(userService.findByTelegramId(CHAT_ID)).willReturn(Optional.empty());
    }

    // ─── order selection ─────────────────────────────────────────────────────

    @Test
    @DisplayName("route: selects handler with lowest order when multiple handlers match")
    void route_selectsLowestOrderHandler() {
        BotUpdateHandler highPriority = mockHandler(true, 5);
        BotUpdateHandler lowPriority  = mockHandler(true, 50);

        CommandRouter router = new CommandRouter(
            List.of(lowPriority, highPriority),   // deliberately unordered
            sessionService, userService, chatMemberService);

        router.route(messageUpdate("/test"), sender);

        then(highPriority).should().handle(any(BotUpdateContext.class));
        then(lowPriority).should(never()).handle(any(BotUpdateContext.class));
    }

    @Test
    @DisplayName("route: when only one handler matches, delegates to it")
    void route_singleMatch_delegates() {
        BotUpdateHandler match    = mockHandler(true, 100);
        BotUpdateHandler noMatch  = mockHandler(false, 50);

        CommandRouter router = new CommandRouter(
            List.of(match, noMatch),
            sessionService, userService, chatMemberService);

        router.route(messageUpdate("/cmd"), sender);

        then(match).should().handle(any(BotUpdateContext.class));
        then(noMatch).should(never()).handle(any(BotUpdateContext.class));
    }

    @Test
    @DisplayName("route: no matching handler → skips update without exception")
    void route_noMatch_skipsQuietly() {
        BotUpdateHandler noMatch = mockHandler(false, 100);

        CommandRouter router = new CommandRouter(
            List.of(noMatch),
            sessionService, userService, chatMemberService);

        assertThatCode(() -> router.route(messageUpdate("/unknown"), sender))
            .doesNotThrowAnyException();
        then(noMatch).should(never()).handle(any(BotUpdateContext.class));
    }

    // ─── null chatId ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("route: update with null chatId is skipped")
    void route_nullChatId_skips() {
        BotUpdateHandler handler = mockHandler(true, 100);
        CommandRouter router = new CommandRouter(
            List.of(handler),
            sessionService, userService, chatMemberService);

        Update emptyUpdate = new Update();   // no message / callback → chatId = null

        assertThatCode(() -> router.route(emptyUpdate, sender))
            .doesNotThrowAnyException();
        then(handler).should(never()).handle(any(BotUpdateContext.class));
        then(sessionService).should(never()).getSession(any());
    }

    // ─── error isolation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("route: handler exception is caught — does not propagate to caller")
    void route_handlerThrows_doesNotPropagate() {
        BotUpdateHandler throwing = mockHandler(true, 100);
        willThrow(new RuntimeException("boom")).given(throwing).handle(any());

        CommandRouter router = new CommandRouter(
            List.of(throwing),
            sessionService, userService, chatMemberService);

        assertThatCode(() -> router.route(messageUpdate("/boom"), sender))
            .doesNotThrowAnyException();
    }

    // ─── chatId extraction ────────────────────────────────────────────────────

    @Test
    @DisplayName("route: extracts chatId from callback query")
    void route_callbackQuery_extractsChatId() {
        BotUpdateHandler handler = mockHandler(true, 100);

        given(sessionService.getSession(CHAT_ID)).willReturn(idleSession());

        CommandRouter router = new CommandRouter(
            List.of(handler),
            sessionService, userService, chatMemberService);

        router.route(callbackUpdate("SOME_DATA"), sender);

        then(handler).should().handle(any(BotUpdateContext.class));
    }

    // ─── context assembly ────────────────────────────────────────────────────

    @Test
    @DisplayName("route: context carries loaded session and sender")
    void route_contextAssembly_includesSessionAndSender() {
        UserBotSession expected = idleSession();
        given(sessionService.getSession(CHAT_ID)).willReturn(expected);

        BotUpdateContext[] captured = new BotUpdateContext[1];
        BotUpdateHandler handler = mockHandler(true, 100);
        willAnswer(inv -> { captured[0] = inv.getArgument(0); return null; })
            .given(handler).handle(any());

        CommandRouter router = new CommandRouter(
            List.of(handler),
            sessionService, userService, chatMemberService);

        router.route(messageUpdate("/any"), sender);

        org.assertj.core.api.Assertions.assertThat(captured[0]).isNotNull();
        org.assertj.core.api.Assertions.assertThat(captured[0].session()).isEqualTo(expected);
        org.assertj.core.api.Assertions.assertThat(captured[0].sender()).isEqualTo(sender);
        org.assertj.core.api.Assertions.assertThat(captured[0].chatId()).isEqualTo(CHAT_ID);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static BotUpdateHandler mockHandler(boolean canHandle, int order) {
        BotUpdateHandler h = mock(BotUpdateHandler.class);
        given(h.canHandle(any(Update.class))).willReturn(canHandle);
        given(h.order()).willReturn(order);
        return h;
    }

    private static Update messageUpdate(String text) {
        Chat chat = new Chat();
        chat.setId(CHAT_ID);

        User from = new User();
        from.setId(CHAT_ID);
        from.setUserName("testuser");

        Message message = new Message();
        message.setChat(chat);
        message.setFrom(from);
        message.setText(text);

        Update update = new Update();
        update.setMessage(message);
        return update;
    }

    private static Update callbackUpdate(String data) {
        User from = new User();
        from.setId(CHAT_ID);

        Chat chat = new Chat();
        chat.setId(CHAT_ID);

        Message msg = new Message();
        msg.setChat(chat);

        CallbackQuery cb = new CallbackQuery();
        cb.setFrom(from);
        cb.setMessage(msg);
        cb.setData(data);

        Update update = new Update();
        update.setCallbackQuery(cb);
        return update;
    }

    private static UserBotSession idleSession() {
        return UserBotSession.builder()
            .chatId(CHAT_ID)
            .state(BotState.IDLE)
            .context(new HashMap<>())
            .updatedAt(Instant.now())
            .build();
    }
}
