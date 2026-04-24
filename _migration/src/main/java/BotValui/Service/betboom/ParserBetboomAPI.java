package BotValui.Service.betboom;

import BotValui.Service.Parser;
import BotValui.Service.betboom.ws.StaticWsBridge;
import BotValui.components.Event;
import BotValui.components.Page;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import proto.betboom.Current;
import proto.betboom.Envelope;
import proto.betboom.MatchesBody;
import proto.betboom.MatchesFrame;
import proto.betboom.ServerFrame;
import proto.betboom.Sport;
import proto.betboom.SportAllBody;
import proto.betboom.TournamentListFrame;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

@Slf4j
public class ParserBetboomAPI implements Parser {

    private static final long DEFAULT_WS_TIMEOUT = 3_000L;
    private static final Current.TypeLine DEFAULT_TYPE_LINE = Current.TypeLine.LINE;

    private URL link;
    private String typeLine = "";
    private String sportIdZip = "";
    private String sportNameZip = "";
    private String countryZip = "";
    private String champZip = "";

    /**
     * Кеш одного ответа tournamentMatches на экземпляр парсера.
     * Убирает двойной запрос:
     *  - getTournamentTitle()
     *  - getMatchEventsForTournament()
     */
    private volatile boolean tournamentMatchesLoaded = false;
    private volatile MatchesFrame cachedTournamentMatchesFrame = null;

    public ParserBetboomAPI(String link) throws MalformedURLException {
        initLink(link);
    }

    /**
     * BetBoom WS / protobuf reference
     *
     * Проверено через отдельный proto-аудит raw wire dump.
     *
     * 1) Общая логика
     * - Для матчей нельзя доверять только типу envelope.
     * - Нужно дополнительно сверять:
     *   section.tournament.id == ожидаемый champId
     *
     * 2) Каноническая ссылка матча
     * - sport alias:  MatchesFrame.sport.sport.alias
     * - countryId:    MatchesFrame.country.country.id
     * - tournamentId: MatchesFrame.section.tournament.id
     * - eventId:      MatchesBody.Match.header.id
     *
     * Формат:
     * https://betboom.ru/sport/{sportAlias}/{countryId}/{tournamentId}/{eventId}?period=all
     *
     * 3) Турниры спорта
     * - Для URL турнира нужен:
     *   tournament.country_id
     *   а не region_id
     *
     * 4) Проверенная структура MatchHeader
     * - 1  = eventId
     * - 2  = lineGroup
     * - 5  = sr_match_id
     * - 6  = status_id
     * - 8  = sport_id
     * - 9  = country/category id
     * - 10 = tournament_id
     * - 12 = status.text
     * - 13 = starts_at
     * - 16 = teams
     * - 19 = live
     *
     * 5) Проверенная структура Team
     * - 1  = id
     * - 2  = sr_id
     * - 3  = name
     * - 4  = name_short
     * - 5  = rank/meta text ("WTA: 17", "ATP: 50"), это НЕ form
     * - 6  = unknown flag (обычно 1)
     * - 7  = logo_webp
     * - 8  = color_hex
     * - 9  = fixed32
     * - 10 = fixed32
     *
     * 6) Важное правило
     * - Принадлежность матча турниру сверять по:
     *   match.header.tournament_id
     *   и дополнительно по section.tournament.id
     */

    // ====================== ИНИЦИАЛИЗАЦИЯ ======================

    private void initLink(String link) throws MalformedURLException {
        String raw = link;

        if (!link.startsWith("https://")) {
            link = "https://" + link;
        }
        if (link.endsWith("/")) {
            link = link.substring(0, link.length() - 1);
        }

        try {
            this.link = new URL(link);
            log.debug("BetBoom parser: initLink ok. raw='{}', normalized='{}'", raw, this.link);
            disassemblyLink();
        } catch (MalformedURLException e) {
            log.warn("BetBoom parser: invalid link format: '{}'", link, e);
            throw e;
        }
    }

    private void disassemblyLink() {
        String[] parts = Arrays.stream(link.getPath().split("/"))
                .filter(s -> s != null && !s.isBlank())
                .toArray(String[]::new);

        // ожидаем путь вида:
        // /sport/{sportAlias}/{countryId}/{champId}[/{eventId}]
        if (parts.length > 0) {
            typeLine = parts[0];
        }
        if (parts.length > 1) {
            sportNameZip = parts[1];
            int sportId = BetBoomSportsMap.getSportId(sportNameZip).orElse(0);
            sportIdZip = String.valueOf(sportId);

            if (sportId == 0) {
                log.warn("Unknown sport alias in URL: sportNameZip='{}', path='{}'", sportNameZip, link.getPath());
            }
        }
        if (parts.length > 2) {
            countryZip = parts[2];
        }
        if (parts.length > 3) {
            champZip = parts[3];
        }

        log.debug("Disassembled link: typeLine='{}', sportNameZip='{}', sportIdZip='{}', countryZip='{}', champZip='{}'",
                typeLine, sportNameZip, sportIdZip, countryZip, champZip);
    }

    // ====================== ПУБЛИЧНЫЕ STATIC API ======================

    /**
     * Получить все виды спорта: имя -> id.
     */
    public static TreeMap<String, String> getAllSport() {
        String op = "getAllSport";
        TreeMap<String, String> allSport = new TreeMap<>();

        try {
            byte[] request = BetBoomSubscribeBuilder.sportAllBytes(DEFAULT_TYPE_LINE, 1);

            byte[] response = sendWsRequestFiltered(
                    op,
                    request,
                    Envelope::hasResponseSportAll
            );
            if (response == null) {
                return allSport;
            }

            Envelope envelope = parseEnvelope(op, response).orElse(null);
            if (envelope == null) {
                return allSport;
            }

            ServerFrame frame = parseServerFrame(op, envelope.getResponseSportAll().toByteArray()).orElse(null);
            if (frame == null) {
                return allSport;
            }

            byte[] body = firstNonEmptyBody(op, frame).orElse(null);
            if (body == null) {
                return allSport;
            }

            SportAllBody sports = parseSportAllBody(op, body).orElse(null);
            if (sports == null) {
                return allSport;
            }

            sports.getRowsList().forEach(row -> {
                var sport = row.getSport();
                if (sport.getId() != 0 && !sport.getName().isBlank()) {
                    allSport.put(sport.getName(), String.valueOf(sport.getId()));
                }
            });

            log.debug("{}: parsed {} sports", op, allSport.size());
        } catch (Exception e) {
            log.error("❌ {}(): unexpected error", op, e);
        }

        return allSport;
    }

    /**
     * Получить все турниры для вида спорта.
     */
    public static TreeMap<String, String> getAllChampSport(String sportZip) {
        String op = "getAllChampSport";
        TreeMap<String, String> allChamp = new TreeMap<>();

        int sportId;
        try {
            sportId = Integer.parseInt(sportZip);
        } catch (NumberFormatException e) {
            log.warn("{}(): invalid sportZip='{}'", op, sportZip, e);
            return allChamp;
        }

        try {
            byte[] request = BetBoomSubscribeBuilder.sportTournamentsBytes(DEFAULT_TYPE_LINE, sportId);

            byte[] resp = sendWsRequestFiltered(
                    op + "[" + sportId + "]",
                    request,
                    Envelope::hasResponseSportTournaments
            );
            if (resp == null) {
                return allChamp;
            }

            Envelope env = parseEnvelope(op, resp).orElse(null);
            if (env == null || !env.hasResponseSportTournaments()) {
                log.debug("{}(): envelope has no responseSportTournaments, kind={}", op, env != null ? env.getKindCase() : null);
                return allChamp;
            }

            ServerFrame sf = parseServerFrame(op, env.getResponseSportTournaments().toByteArray()).orElse(null);
            if (sf == null) {
                return allChamp;
            }

            byte[] body = firstNonEmptyBody(op, sf).orElse(null);
            if (body == null) {
                return allChamp;
            }

            TournamentListFrame tlf = parseTournamentListFrame(op, body).orElse(null);
            if (tlf == null) {
                return allChamp;
            }

            tlf.getList().getEntriesList().forEach(row -> {
                var t = row.getTournament();
                if (!t.getTitle().isEmpty()) {
                    allChamp.put(t.getTitle(), String.valueOf(t.getId()));
                }
            });

            log.debug("{}[{}]: parsed {} tournaments", op, sportId, allChamp.size());
        } catch (Exception e) {
            log.error("❌ {}(): unexpected error for sportId={}", op, sportId, e);
        }

        return allChamp;
    }

    /**
     * Попробовать получить каноническую ссылку по id турнира.
     */
    public static String getLink(String zip) {
        int id;
        try {
            id = Integer.parseInt(zip);
        } catch (NumberFormatException e) {
            log.warn("getLink(): invalid zip='{}'", zip, e);
            return "";
        }

        Optional<String> wsLink = getLinkByChampIdZip(id);
        if (wsLink.isPresent()) {
            return wsLink.get();
        }

        Optional<String> aliasOpt = BetBoomSportsMap.getSport(id);
        return aliasOpt
                .map(alias -> "https://betboom.ru/sport/" + alias)
                .orElse("");
    }

    private static Optional<String> getLinkByChampIdZip(int zip) {
        String op = "getLinkByChampIdZip";

        try {
            byte[] request = BetBoomSubscribeBuilder.tournamentMatchesBytes(DEFAULT_TYPE_LINE, zip);
            byte[] response = sendWsRequestFiltered(
                    op + "[" + zip + "]",
                    request,
                    env -> hasExpectedTournamentMatchesEnvelope(env, zip)
            );
            if (response == null) {
                return Optional.empty();
            }

            Envelope env = parseEnvelope(op, response).orElse(null);
            if (env == null || !env.hasResponseTournamentMatches()) {
                log.debug("{}[{}]: envelope has no responseTournamentMatches, kind={}",
                        op, zip, env != null ? env.getKindCase() : null);
                return Optional.empty();
            }

            ServerFrame sf = parseServerFrame(op, env.getResponseTournamentMatches().toByteArray()).orElse(null);
            if (sf == null) {
                return Optional.empty();
            }

            byte[] body = firstNonEmptyBody(op, sf).orElse(null);
            if (body == null) {
                return Optional.empty();
            }

            MatchesFrame mf = parseMatchesFrame(op, body).orElse(null);
            if (mf == null) {
                return Optional.empty();
            }

            if (mf.hasSport() && mf.getSport().hasSport()
                    && mf.hasCountry() && mf.getCountry().hasCountry()) {

                String sportAlias = mf.getSport().getSport().getAlias();
                int countryId = mf.getCountry().getCountry().getId();

                if (!sportAlias.isBlank() && countryId != 0) {
                    String link = "https://betboom.ru/sport/"
                            + sportAlias + "/" + countryId + "/" + zip + "?period=all";
                    log.debug("{}[{}]: resolved link={}", op, zip, link);
                    return Optional.of(link);
                }
            }

            log.debug("{}[{}]: cannot resolve sportAlias/countryId from MatchesFrame", op, zip);
        } catch (Exception e) {
            log.error("❌ {}[{}]: WS/parsing error", op, zip, e);
        }

        return Optional.empty();
    }

    // ====================== TITLE ======================

    public String getTitle() {
        if (champZip.isEmpty()) {
            return getSportTitle();
        } else {
            return getTournamentTitle();
        }
    }

    private String getSportTitle() {
        String op = "getTitle[sport]";
        try {
            byte[] request = BetBoomSubscribeBuilder.sportAllBytes(DEFAULT_TYPE_LINE, 1);
            byte[] response = sendWsRequestFiltered(
                    op,
                    request,
                    Envelope::hasResponseSportAll
            );
            if (response == null) {
                return EMPTY_LINE;
            }

            Envelope envelope = parseEnvelope(op, response).orElse(null);
            if (envelope == null) {
                return EMPTY_LINE;
            }

            ServerFrame frame = parseServerFrame(op, envelope.getResponseSportAll().toByteArray()).orElse(null);
            if (frame == null) {
                return EMPTY_LINE;
            }

            byte[] body = firstNonEmptyBody(op, frame).orElse(null);
            if (body == null) {
                return EMPTY_LINE;
            }

            SportAllBody sports = parseSportAllBody(op, body).orElse(null);
            if (sports == null) {
                return EMPTY_LINE;
            }

            int targetId;
            try {
                targetId = Integer.parseInt(sportIdZip);
            } catch (NumberFormatException e) {
                log.warn("{}: invalid sportIdZip='{}'", op, sportIdZip, e);
                return EMPTY_LINE;
            }

            return sports.getRowsList().stream()
                    .map(SportAllBody.Row::getSport)
                    .filter(sport -> sport.getId() == targetId && !sport.getName().isBlank())
                    .map(Sport::getName)
                    .findFirst()
                    .orElse(EMPTY_LINE);
        } catch (Exception e) {
            log.error("❌ {}: unexpected error", op, e);
            return EMPTY_LINE;
        }
    }

    private String getTournamentTitle() {
        String op = "getTitle[champ]";
        try {
            MatchesFrame mf = getTournamentMatchesFrameCached().orElse(null);
            if (mf == null) {
                return EMPTY_LINE;
            }

            if (mf.hasSection() && mf.getSection().hasTournament()) {
                return mf.getSection().getTournament().getTitle();
            }

            log.debug("{}: section/tournament not present in MatchesFrame", op);
        } catch (Exception e) {
            log.error("❌ {}: unexpected error for champZip='{}'", op, champZip, e);
        }
        return EMPTY_LINE;
    }

    // ====================== EVENTS ======================

    private TreeSet<Event> getEvents() {
        if (champZip.isEmpty()) {
            return getTournamentEventsForSport();
        } else {
            return getMatchEventsForTournament();
        }
    }

    private TreeSet<Event> getTournamentEventsForSport() {
        String op = "getEvents[sport]";
        TreeSet<Event> events = new TreeSet<>();

        int sportId;
        try {
            sportId = Integer.parseInt(sportIdZip);
        } catch (NumberFormatException e) {
            log.warn("{}: invalid sportIdZip='{}'", op, sportIdZip, e);
            return events;
        }

        try {
            byte[] request = BetBoomSubscribeBuilder.sportTournamentsBytes(DEFAULT_TYPE_LINE, sportId);
            byte[] resp = sendWsRequestFiltered(
                    op + "[" + sportId + "]",
                    request,
                    Envelope::hasResponseSportTournaments
            );
            if (resp == null) {
                return events;
            }

            Envelope env = parseEnvelope(op, resp).orElse(null);
            if (env == null || !env.hasResponseSportTournaments()) {
                log.debug("{}[{}]: envelope has no responseSportTournaments, kind={}",
                        op, sportId, env != null ? env.getKindCase() : null);
                return events;
            }

            ServerFrame sf = parseServerFrame(op, env.getResponseSportTournaments().toByteArray()).orElse(null);
            if (sf == null) {
                return events;
            }

            byte[] body = firstNonEmptyBody(op, sf).orElse(null);
            if (body == null) {
                return events;
            }

            TournamentListFrame tlf = parseTournamentListFrame(op, body).orElse(null);
            if (tlf == null) {
                return events;
            }

            tlf.getList().getEntriesList().forEach(row -> {
                var t = row.getTournament();
                int countryId = t.getCountryId();

                if (!t.getTitle().isEmpty() && countryId != 0) {
                    String url = "https://betboom.ru/sport/"
                            + sportNameZip + "/"
                            + countryId + "/"
                            + t.getId()
                            + "?period=all";

                    events.add(new Event(
                            String.valueOf(t.getId()),
                            url,
                            t.getTitle()
                    ));
                }
            });

            log.debug("{}[{}]: parsed {} tournaments as events", op, sportId, events.size());
        } catch (Exception e) {
            log.error("❌ {}: unexpected error for sportIdZip='{}'", op, sportIdZip, e);
        }

        return events;
    }

    private TreeSet<Event> getMatchEventsForTournament() {
        String op = "getEvents[champ]";
        TreeSet<Event> events = new TreeSet<>();

        int champId;
        try {
            champId = Integer.parseInt(champZip);
        } catch (NumberFormatException e) {
            log.warn("{}: invalid champZip='{}'", op, champZip, e);
            return events;
        }

        try {
            MatchesFrame mf = getTournamentMatchesFrameCached().orElse(null);
            if (mf == null || !mf.hasSection()) {
                log.debug("{}[{}]: MatchesFrame missing or has no section", op, champId);
                return events;
            }

            if (!mf.getSection().hasTournament()) {
                log.debug("{}[{}]: section has no tournament", op, champId);
                return events;
            }

            int actualSectionTournamentId = mf.getSection().getTournament().getId();
            if (actualSectionTournamentId != champId) {
                log.debug("{}[{}]: section tournament mismatch: actual={}", op, champId, actualSectionTournamentId);
                return events;
            }

            for (MatchesBody.Match match : mf.getSection().getMatchesList()) {
                if (!match.hasHeader()) {
                    log.debug("{}[{}]: skip match without header", op, champId);
                    continue;
                }

                int eventId = match.getHeader().getId();
                int tournamentId = match.getHeader().getTournamentId();

                if (tournamentId != 0 && tournamentId != champId) {
                    log.debug("{}[{}]: skip eventId={} because tournamentId={} mismatch",
                            op, champId, eventId, tournamentId);
                    continue;
                }

                String nameTeam1 = match.getHeader().hasTeams() && match.getHeader().getTeams().hasHome()
                        ? match.getHeader().getTeams().getHome().getName()
                        : "";
                String nameTeam2 = match.getHeader().hasTeams() && match.getHeader().getTeams().hasAway()
                        ? match.getHeader().getTeams().getAway().getName()
                        : "";

                String title = (!nameTeam1.isBlank() || !nameTeam2.isBlank())
                        ? nameTeam1 + " - " + nameTeam2
                        : "matchId=" + eventId;

                String eventLink = buildCanonicalEventLink(mf, match);

                events.add(new Event(
                        String.valueOf(eventId),
                        eventLink,
                        title
                ));
            }

            log.debug("{}[{}]: parsed {} matches", op, champId, events.size());
        } catch (Exception e) {
            log.error("❌ {}: unexpected error for champZip='{}'", op, champZip, e);
        }

        return events;
    }

    private String buildCanonicalEventLink(MatchesFrame mf, MatchesBody.Match match) {
        String sportAlias = sportNameZip;
        if (mf.hasSport() && mf.getSport().hasSport()) {
            String aliasFromResponse = mf.getSport().getSport().getAlias();
            if (!aliasFromResponse.isBlank()) {
                sportAlias = aliasFromResponse;
            }
        }

        int countryId = 0;
        if (mf.hasCountry() && mf.getCountry().hasCountry()) {
            countryId = mf.getCountry().getCountry().getId();
        }

        int tournamentId = 0;
        if (mf.hasSection() && mf.getSection().hasTournament()) {
            tournamentId = mf.getSection().getTournament().getId();
        }

        int eventId = match.getHeader().getId();

        StringBuilder url = new StringBuilder("https://betboom.ru/sport/")
                .append(sportAlias)
                .append("/")
                .append(countryId)
                .append("/")
                .append(tournamentId)
                .append("/")
                .append(eventId);

        String query = link.getQuery();
        if (query != null && !query.isBlank()) {
            url.append("?").append(query);
        } else {
            url.append("?period=all");
        }

        return url.toString();
    }

    // ====================== КЕШ tournamentMatches ======================

    private Optional<MatchesFrame> getTournamentMatchesFrameCached() {
        if (tournamentMatchesLoaded) {
            return Optional.ofNullable(cachedTournamentMatchesFrame);
        }

        synchronized (this) {
            if (tournamentMatchesLoaded) {
                return Optional.ofNullable(cachedTournamentMatchesFrame);
            }

            String op = "getTournamentMatchesFrameCached";

            int champId;
            try {
                champId = Integer.parseInt(champZip);
            } catch (NumberFormatException e) {
                log.warn("{}: invalid champZip='{}'", op, champZip, e);
                tournamentMatchesLoaded = true;
                cachedTournamentMatchesFrame = null;
                return Optional.empty();
            }

            try {
                byte[] request = BetBoomSubscribeBuilder.tournamentMatchesBytes(DEFAULT_TYPE_LINE, champId);
                byte[] response = sendWsRequestFiltered(
                        op + "[" + champId + "]",
                        request,
                        env -> hasExpectedTournamentMatchesEnvelope(env, champId)
                );

                if (response == null) {
                    tournamentMatchesLoaded = true;
                    cachedTournamentMatchesFrame = null;
                    return Optional.empty();
                }

                Envelope env = parseEnvelope(op, response).orElse(null);
                if (env == null || !env.hasResponseTournamentMatches()) {
                    tournamentMatchesLoaded = true;
                    cachedTournamentMatchesFrame = null;
                    return Optional.empty();
                }

                ServerFrame sf = parseServerFrame(op, env.getResponseTournamentMatches().toByteArray()).orElse(null);
                if (sf == null) {
                    tournamentMatchesLoaded = true;
                    cachedTournamentMatchesFrame = null;
                    return Optional.empty();
                }

                byte[] body = firstNonEmptyBody(op, sf).orElse(null);
                if (body == null) {
                    tournamentMatchesLoaded = true;
                    cachedTournamentMatchesFrame = null;
                    return Optional.empty();
                }

                MatchesFrame mf = parseMatchesFrame(op, body).orElse(null);

                tournamentMatchesLoaded = true;
                cachedTournamentMatchesFrame = mf;
                return Optional.ofNullable(mf);
            } catch (Exception e) {
                log.error("❌ {}: unexpected error for champZip='{}'", op, champZip, e);
                tournamentMatchesLoaded = true;
                cachedTournamentMatchesFrame = null;
                return Optional.empty();
            }
        }
    }

    // ====================== Parser интерфейс ======================

    @Override
    public Page getForControllerPage() {
        return getForControllerPage(null);
    }

    @Override
    public Page getForControllerPage(String customTitlePart) {
        String autoTitle = getTitle();
        String baseTitle = isTitleValid(customTitlePart) ? customTitlePart : "BetBoom: " + autoTitle;
        String finalTitle = isTitleValid(autoTitle)
                ? baseTitle
                : (baseTitle.startsWith(WARNING_ICON) ? baseTitle : WARNING_ICON + baseTitle);

        Page page = new Page(link.toString(), champZip, finalTitle);
        page.setEvents(getEvents());
        return page;
    }

    // ====================== ВСПОМОГАТЕЛЬНЫЕ WS/PROTO МЕТОДЫ ======================

    private static byte[] sendWsRequestFiltered(
            String op,
            byte[] request,
            Predicate<Envelope> okPredicate
    ) {
        try {
            byte[] resp = StaticWsBridge.get()
                    .sendAndAwaitFiltered(request, DEFAULT_WS_TIMEOUT, okPredicate);

            if (resp != null) {
                log.debug("{}: got filtered WS response, size={} bytes", op, resp.length);
            }
            return resp;
        } catch (Exception e) {
            log.error("❌ {}: WS sendAndAwaitFiltered error, requestBase64={}",
                    op, base64Safe(request), e);
            return null;
        }
    }

    private static boolean hasExpectedTournamentMatchesEnvelope(Envelope env, int expectedChampId) {
        if (env == null || !env.hasResponseTournamentMatches()) {
            return false;
        }

        try {
            ServerFrame sf = ServerFrame.parseFrom(env.getResponseTournamentMatches().toByteArray());
            byte[] body = firstNonEmptyBodyRaw(sf);
            if (body == null) {
                return false;
            }

            MatchesFrame mf = MatchesFrame.parseFrom(body);
            return mf.hasSection()
                    && mf.getSection().hasTournament()
                    && mf.getSection().getTournament().getId() == expectedChampId;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] firstNonEmptyBodyRaw(ServerFrame sf) {
        return sf.getBodyList().stream()
                .filter(bs -> bs != null && !bs.isEmpty())
                .findFirst()
                .map(ByteString::toByteArray)
                .orElse(null);
    }

    private static Optional<Envelope> parseEnvelope(String op, byte[] response) {
        try {
            Envelope env = Envelope.parseFrom(response);
            log.debug("{}: envelope kind={}", op, env.getKindCase());
            return Optional.of(env);
        } catch (InvalidProtocolBufferException e) {
            log.error("❌ {}: failed to parse Envelope, respSize={}, respBase64={}",
                    op, response.length, base64Safe(response), e);
            return Optional.empty();
        }
    }

    private static Optional<ServerFrame> parseServerFrame(String op, byte[] body) {
        try {
            ServerFrame sf = ServerFrame.parseFrom(body);
            log.debug("{}: ServerFrame status={}, subStatus={}, bodyCount={}",
                    op, sf.getStatus(), sf.getSubStatus(), sf.getBodyCount());
            return Optional.of(sf);
        } catch (InvalidProtocolBufferException e) {
            log.error("❌ {}: failed to parse ServerFrame, bodySize={}, bodyBase64={}",
                    op, body.length, base64Safe(body), e);
            return Optional.empty();
        }
    }

    private static Optional<byte[]> firstNonEmptyBody(String op, ServerFrame sf) {
        byte[] body = firstNonEmptyBodyRaw(sf);

        if (body == null) {
            log.debug("{}: ServerFrame has no non-empty body, bodyCount={}", op, sf.getBodyCount());
            return Optional.empty();
        }

        log.debug("{}: first non-empty body size={}", op, body.length);
        return Optional.of(body);
    }

    private static Optional<SportAllBody> parseSportAllBody(String op, byte[] body) {
        try {
            return Optional.of(SportAllBody.parseFrom(body));
        } catch (InvalidProtocolBufferException e) {
            log.error("❌ {}: failed to parse SportAllBody, bodySize={}, bodyBase64={}",
                    op, body.length, base64Safe(body), e);
            return Optional.empty();
        }
    }

    private static Optional<TournamentListFrame> parseTournamentListFrame(String op, byte[] body) {
        try {
            return Optional.of(TournamentListFrame.parseFrom(body));
        } catch (InvalidProtocolBufferException e) {
            log.error("❌ {}: failed to parse TournamentListFrame, bodySize={}, bodyBase64={}",
                    op, body.length, base64Safe(body), e);
            return Optional.empty();
        }
    }

    private static Optional<MatchesFrame> parseMatchesFrame(String op, byte[] body) {
        try {
            return Optional.of(MatchesFrame.parseFrom(body));
        } catch (InvalidProtocolBufferException e) {
            log.error("❌ {}: failed to parse MatchesFrame, bodySize={}, bodyBase64={}",
                    op, body.length, base64Safe(body), e);
            return Optional.empty();
        }
    }

    private static String base64Safe(byte[] data) {
        if (data == null) return "<null>";
        int max = Math.min(data.length, 512);
        byte[] slice = (data.length == max) ? data : Arrays.copyOf(data, max);
        String base64 = Base64.getEncoder().encodeToString(slice);
        if (data.length > max) {
            return base64 + "...(truncated, fullSize=" + data.length + ")";
        }
        return base64;
    }
}