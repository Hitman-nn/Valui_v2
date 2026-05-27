package com.valui.app.miniapp;

import com.valui.betting.dto.BetAccountDto;
import com.valui.betting.dto.BetPersonDto;
import com.valui.betting.dto.analytics.AnalyticsResponse;
import com.valui.betting.repository.BetAccountRepository;
import com.valui.betting.repository.BetPersonRepository;
import com.valui.betting.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
    private final BetAccountRepository      accountRepository;
    private final BetPersonRepository       personRepository;
    private final AnalyticsService          analyticsService;

    @Value("${telegram.admin-chat-id:0}")
    private long adminChatId;

    /** Счета, с которых этот пользователь ставил. */
    @GetMapping("/accounts")
    public List<BetAccountDto> listAccounts(@RequestHeader(INIT_DATA_HEADER) String initData) {
        long userId = validator.validate(initData);
        return accountRepository.findAccountsByTelegramId(userId)
                .stream().map(BetAccountDto::from).toList();
    }

    /**
     * Участники выбранных счетов. Доступно только админу.
     * Не-админ всегда получает пустой список (не 403, чтобы фронт мог определить роль по наличию данных).
     */
    @GetMapping("/persons")
    public List<BetPersonDto> listPersons(
            @RequestHeader(INIT_DATA_HEADER) String initData,
            @RequestParam List<UUID> accountIds) {
        long userId = validator.validate(initData);
        if (adminChatId <= 0 || userId != adminChatId || accountIds.isEmpty()) return List.of();
        return personRepository.findPersonsByAccountIds(accountIds)
                .stream().map(BetPersonDto::from).toList();
    }

    @GetMapping("/analytics")
    public AnalyticsResponse getAnalytics(
            @RequestHeader(INIT_DATA_HEADER) String initData,
            @RequestParam List<UUID>   accountIds,
            @RequestParam(required = false) List<UUID> personIds,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {

        long userId = validator.validate(initData);

        if (accountIds == null || accountIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "accountIds must not be empty");
        }

        boolean isAdmin = adminChatId > 0 && userId == adminChatId;

        if (personIds != null && !personIds.isEmpty() && !isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Person filter requires admin");
        }

        OffsetDateTime resolvedFrom = from != null ? from : OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime resolvedTo   = to   != null ? to   : OffsetDateTime.now(ZoneOffset.UTC).plusYears(10);

        // telegramId=null для админа без фильтра по участникам → видит все ставки на счетах
        Long telegramId = isAdmin ? null : userId;

        return analyticsService.getAnalytics(accountIds, telegramId, personIds, resolvedFrom, resolvedTo);
    }
}
