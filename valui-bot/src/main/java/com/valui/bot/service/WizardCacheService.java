package com.valui.bot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.valui.bot.state.UserBotSession;
import com.valui.common.parser.dto.SportDto;
import com.valui.common.parser.dto.TournamentDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Caches parser results (sports / tournaments) in the user's Redis session so that
 * page navigation does not require a new HTTP call to the bookmaker on every click.
 *
 * Cache lifecycle: sports are cleared when the user leaves bookmaker selection;
 * tournaments are cleared when the user returns to the sport list.
 * Both caches are cleared implicitly whenever the entire wizard context is reset.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WizardCacheService {

    private final BotSessionService sessionService;
    private final ObjectMapper      objectMapper;

    // ─── Sports ──────────────────────────────────────────────────────────────

    public void cacheSports(Long fromId, List<SportDto> sports) {
        writeJson(fromId, UserBotSession.CTX_CACHED_SPORTS_JSON, sports);
    }

    public Optional<List<SportDto>> getCachedSports(Long fromId) {
        return readJson(fromId, UserBotSession.CTX_CACHED_SPORTS_JSON,
                new TypeReference<List<SportDto>>() {});
    }

    public void clearSportsCache(Long fromId) {
        sessionService.putContext(fromId, UserBotSession.CTX_CACHED_SPORTS_JSON, null);
    }

    // ─── Tournaments ──────────────────────────────────────────────────────────

    public void cacheTournaments(Long fromId, List<TournamentDto> tournaments) {
        writeJson(fromId, UserBotSession.CTX_CACHED_TOURNAMENTS_JSON, tournaments);
    }

    public Optional<List<TournamentDto>> getCachedTournaments(Long fromId) {
        return readJson(fromId, UserBotSession.CTX_CACHED_TOURNAMENTS_JSON,
                new TypeReference<List<TournamentDto>>() {});
    }

    public void clearTournamentsCache(Long fromId) {
        sessionService.putContext(fromId, UserBotSession.CTX_CACHED_TOURNAMENTS_JSON, null);
    }

    // ─── private helpers ─────────────────────────────────────────────────────

    private <T> void writeJson(Long fromId, String key, T value) {
        try {
            sessionService.putContext(fromId, key, objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            log.warn("Failed to cache {} for fromId={}: {}", key, fromId, e.getMessage());
        }
    }

    private <T> Optional<T> readJson(Long fromId, String key, TypeReference<T> type) {
        return sessionService.getContext(fromId, key).flatMap(json -> {
            try {
                return Optional.of(objectMapper.readValue(json, type));
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize {} for fromId={}: {}", key, fromId, e.getMessage());
                return Optional.empty();
            }
        });
    }
}
