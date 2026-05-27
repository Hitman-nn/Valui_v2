package com.valui.app.miniapp;

import com.valui.betting.dto.BetAccountDto;
import com.valui.betting.dto.BetPersonDto;
import com.valui.betting.dto.analytics.AnalyticsResponse;
import com.valui.betting.repository.BetAccountRepository;
import com.valui.betting.repository.BetPersonRepository;
import com.valui.betting.repository.BetRepository;
import com.valui.betting.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/miniapp")
@RequiredArgsConstructor
public class MiniAppController {

    private static final String INIT_DATA_HEADER = "X-Telegram-Init-Data";

    private final TelegramInitDataValidator validator;
    private final BetRepository             betRepository;
    private final BetAccountRepository      accountRepository;
    private final BetPersonRepository       personRepository;
    private final AnalyticsService          analyticsService;

    @GetMapping("/accounts")
    public List<BetAccountDto> listAccounts(
            @RequestHeader(INIT_DATA_HEADER) String initData,
            @RequestParam long chatId) {
        long userId = validator.validate(initData);
        assertChatAccess(userId, chatId);
        return accountRepository.findAllByChatIdOrderByNameAsc(chatId)
                .stream().map(BetAccountDto::from).toList();
    }

    @GetMapping("/persons")
    public List<BetPersonDto> listPersons(
            @RequestHeader(INIT_DATA_HEADER) String initData,
            @RequestParam long chatId) {
        long userId = validator.validate(initData);
        assertChatAccess(userId, chatId);
        return personRepository.findAllByChatIdOrderByDisplayNameAsc(chatId)
                .stream().map(BetPersonDto::from).toList();
    }

    @GetMapping("/analytics")
    public AnalyticsResponse getAnalytics(
            @RequestHeader(INIT_DATA_HEADER) String initData,
            @RequestParam long   chatId,
            @RequestParam String scope,
            @RequestParam UUID   id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {

        long userId = validator.validate(initData);
        assertChatAccess(userId, chatId);

        OffsetDateTime resolvedFrom = from != null ? from : OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime resolvedTo   = to   != null ? to   : OffsetDateTime.now(ZoneOffset.UTC).plusYears(10);

        AnalyticsService.Scope analyticsScope;
        try {
            analyticsScope = AnalyticsService.Scope.valueOf(scope.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scope must be ACCOUNT or PERSON");
        }

        return analyticsService.getAnalytics(analyticsScope, id, chatId, resolvedFrom, resolvedTo);
    }

    private void assertChatAccess(long userId, long chatId) {
        // Private chat: chatId == telegramId — always has access
        if (userId == chatId) return;
        // Group chat: verify the user has placed bets in this chat
        if (!betRepository.existsByTelegramIdAndChatId(userId, chatId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No access to chatId " + chatId);
        }
    }
}
