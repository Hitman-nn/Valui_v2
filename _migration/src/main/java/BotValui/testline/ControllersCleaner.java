package BotValui.testline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class ControllersCleaner {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, true);

    public static void main(String[] args) throws Exception {
        Map<String, String> p = parseArgs(args);
        if (p.containsKey("help")) { printHelp(); return; }

        Path controllersFile = Paths.get(p.getOrDefault("controllers-file", "chatConfig.json"));
        int days = Integer.parseInt(p.getOrDefault("days", "30"));
        boolean dryRun = false;//p.containsKey("dry-run");
        boolean clearEventsMode = true;//p.containsKey("clear-events"); // <-- НОВОЕ

        final String matchBy = "link";          // чистка индекса по link (для режима устаревания)
        final String titleFrom = "first-event"; // имя турнира в отчёте из первого события

        Root root;
        try { root = readRoot(controllersFile); }
        catch (NoSuchFileException nf) {
            System.err.printf("[ERROR] controllers-file not found: %s%n", controllersFile.toAbsolutePath());
            return;
        }

        List<Controller> controllers = Optional.ofNullable(root.controller).orElseGet(ArrayList::new);
        if (controllers.isEmpty()) { System.out.println("[INFO] no controllers"); return; }

        // индекс-контроллеры: ruleFilter НЕ пустой
        Set<Integer> indexPositions = detectIndexControllersByRuleFilter(controllers);
        System.out.println(indexPositions.isEmpty()
                ? "[INFO] index controller not detected (ruleFilter all blank)"
                : "[INFO] detected index controllers at positions: " + indexPositions);

        if (clearEventsMode) {
            // ---------- НОВЫЙ РЕЖИМ: очистка событий у всех матч-контроллеров ----------
            int totalClearedControllers = 0;
            long totalEventsRemoved = 0;

            List<Controller> updated = new ArrayList<>(controllers.size());
            for (int i = 0; i < controllers.size(); i++) {
                Controller c = controllers.get(i);
                if (indexPositions.contains(i)) {
                    // индекс-контроллер НЕ трогаем
                    updated.add(c);
                    continue;
                }
                int before = size(c.events);
                if (before > 0) {
                    if (dryRun) {
                        System.out.printf("[DRY] would clear %d events from controller: '%s'%n",
                                before, safe(nameForReport(c)));
                        updated.add(c); // без изменений
                    } else {
                        Controller emptied = new Controller(c.chatid, c.link, c.ruleFilter, c.title, new ArrayList<>());
                        updated.add(emptied);
                        System.out.printf("[CLEAR] cleared %d events from controller: '%s'%n",
                                before, safe(nameForReport(c)));
                    }
                    totalClearedControllers++;
                    totalEventsRemoved += before;
                } else {
                    updated.add(c);
                }
            }

            if (!dryRun) {
                writeRoot(controllersFile, new Root(root.filter, updated));
                System.out.printf("[WRITE] controllers updated (events cleared). Controllers affected: %d, Events removed: %d%n",
                        totalClearedControllers, totalEventsRemoved);
            } else {
                System.out.printf("[DRY] would clear events. Controllers affected: %d, Events to remove: %d%n",
                        totalClearedControllers, totalEventsRemoved);
            }

            // финальный отчёт
            System.out.println("\n=== SUMMARY (clear-events) ===");
            System.out.printf("Index controllers: %s (kept intact)%n", indexPositions);
            System.out.printf("Controllers affected: %d | Events removed: %d%n",
                    totalClearedControllers, totalEventsRemoved);
            if (dryRun) System.out.println("\n[DRY-RUN] Nothing was actually rewritten.");
            return;
        }

        // ---------- СТАРЫЙ РЕЖИМ: удаление устаревших контроллеров + чистка индекса ----------
        int daysThreshold = days;
        Instant cutoff = Instant.now().minus(Duration.ofDays(daysThreshold));

        List<RemovedTournament> stale = new ArrayList<>();
        List<Controller> keptControllers = new ArrayList<>();

        // 1) собираем устаревшие (кроме индекс-контроллеров)
        for (int i = 0; i < controllers.size(); i++) {
            Controller c = controllers.get(i);
            if (indexPositions.contains(i)) {
                keptControllers.add(c); // индекс не удаляем, почистим его events позже
                continue;
            }
            if (isStale(c, cutoff)) {
                RemovedTournament rt = toRemovedTournament(controllersFile, c, titleFrom, matchBy);
                stale.add(rt);
                System.out.printf("[STALE] controller '%s' | linkKey=%s%n", rt.humanName, rt.matchKey);
            } else {
                keptControllers.add(c);
            }
        }

        // 2) чистим индекс-контроллеры по link
        if (!stale.isEmpty() && !indexPositions.isEmpty()) {
            Set<String> removedKeys = stale.stream().map(rt -> rt.matchKey).collect(Collectors.toSet());
            List<Controller> finalControllers = new ArrayList<>(controllers.size());
            for (int i = 0; i < controllers.size(); i++) {
                Controller c = controllers.get(i);
                if (indexPositions.contains(i)) {
                    Controller cleaned = cleanIndexControllerByLink(c, removedKeys);
                    int removed = size(c.events) - size(cleaned.events);
                    if (removed > 0) {
                        System.out.printf("[INDEX] %s — removed %d tournaments by link%n", safe(c.title), removed);
                    }
                    finalControllers.add(cleaned);
                } else if (!isStale(c, cutoff)) {
                    finalControllers.add(c);
                }
                // устаревшие пропускаем
            }
            keptControllers = finalControllers;
        }

        // 3) запись/отчёт
        if (!dryRun) {
            writeRoot(controllersFile, new Root(root.filter, keptControllers));
            System.out.printf("[WRITE] controllers updated: kept %d/%d%n", keptControllers.size(), controllers.size());
        } else {
            System.out.printf("[DRY] would keep %d/%d controllers%n", keptControllers.size(), controllers.size());
        }

        System.out.println("\n=== SUMMARY (stale-removal) ===");
        System.out.printf("Cutoff: %s (%d days)%n", cutoff, daysThreshold);
        System.out.printf("Total controllers: %d | Removed: %d | Kept: %d | Index controllers: %s%n",
                controllers.size(), stale.size(), keptControllers.size(), indexPositions);
        for (RemovedTournament rt : stale) {
            System.out.printf(" - %s | linkKey=%s%n", rt.humanName, rt.matchKey);
        }
        if (dryRun) System.out.println("\n[DRY-RUN] Nothing was actually rewritten.");
    }

    // ===== ВСПОМОГАТЕЛЬНОЕ =====

    private static String nameForReport(Controller c) {
        if (c.events != null && !c.events.isEmpty() && c.events.get(0).title != null && !c.events.get(0).title.isBlank()) {
            return c.events.get(0).title;
        }
        return c.title;
    }

    private static Set<Integer> detectIndexControllersByRuleFilter(List<Controller> controllers) {
        Set<Integer> res = new HashSet<>();
        for (int i = 0; i < controllers.size(); i++) {
            Controller c = controllers.get(i);
            if (c.ruleFilter != null && !c.ruleFilter.isBlank()) res.add(i);
        }
        return res;
    }

    private static Controller cleanIndexControllerByLink(Controller ctrl, Set<String> removedKeysByLink) {
        if (ctrl.events == null || ctrl.events.isEmpty()) return ctrl;
        Predicate<Event> keep = e -> e.link == null || !removedKeysByLink.contains(norm(e.link));
        List<Event> kept = ctrl.events.stream().filter(keep).toList();
        return new Controller(ctrl.chatid, ctrl.link, ctrl.ruleFilter, ctrl.title, kept);
    }

    private static RemovedTournament toRemovedTournament(Path file, Controller c, String titleFrom, String matchBy) {
        String humanName = ("first-event".equals(titleFrom) && c.events != null && !c.events.isEmpty())
                ? safe(c.events.get(0).title) : safe(c.title);
        String link = firstNonBlank(c.link, (c.events != null && !c.events.isEmpty()) ? c.events.get(0).link : null);
        String matchKey = norm(link);
        return new RemovedTournament(file.toString(), humanName, matchKey);
    }

    private static boolean isStale(Controller c, Instant cutoff) {
        if (c.events == null || c.events.isEmpty()) return true;
        Instant last = c.events.stream()
                .map(e -> parseInstantSoft(e.timestamp))
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(Instant.EPOCH);
        return last.isBefore(cutoff);
    }

    private static Instant parseInstantSoft(String ts) {
        if (ts == null || ts.isBlank()) return null;
        try { return Instant.parse(ts); } catch (DateTimeParseException ignore) {}
        try { return OffsetDateTime.parse(ts).toInstant(); } catch (DateTimeParseException ignore) {}
        try {
            LocalDateTime ldt = LocalDateTime.parse(ts);
            return ldt.atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignore) {}
        return null;
    }

    private static Root readRoot(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) { return MAPPER.readValue(in, Root.class); }
    }

    private static void writeRoot(Path file, Root root) throws IOException {
        try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            MAPPER.writeValue(out, root);
        }
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static int size(List<?> l) { return l == null ? 0 : l.size(); }
    private static String norm(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }
    private static String safe(String s) { return s == null ? "<null>" : s; }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> p = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--help", "-h" -> p.put("help", "1");
                case "--dry-run" -> p.put("dry-run", "1");
                case "--controllers-file" -> p.put("controllers-file", args[++i]);
                case "--days" -> p.put("days", args[++i]);
                case "--clear-events" -> p.put("clear-events", "1"); // <-- НОВОЕ
                default -> { System.err.println("Unknown arg: " + a); p.put("help", "1"); }
            }
        }
        return p;
    }

    private static void printHelp() {
        System.out.println("""
                ControllersCleaner — один JSON с контроллерами (включая индекс где events = турниры)

                Режимы:
                  • --clear-events                Очистить events у всех матч-контроллеров (индекс не трогать).
                  • (по умолчанию) Удалить устаревшие контроллеры (>45 дней без событий) и зачистить индекс по link.

                Правила:
                  • Индекс-контроллер: ruleFilter НЕ пустой.
                  • Имя турнира в отчёте: из первого события контроллера.
                  • Очистка индекса (в режиме устаревания): матч по link удалённых контроллеров.

                Опции:
                  --controllers-file <path>   Путь к JSON (default: ./controllers.json)
                  --days <int>                Порог неактивности для режима устаревания (default: 45)
                  --dry-run                   Прогон без записи
                  --clear-events              Режим очистки событий (индекс не трогаем)

                Примеры:
                  java ControllersCleaner --controllers-file ./controllers.json --clear-events --dry-run
                  java ControllersCleaner --controllers-file ./controllers.json --clear-events
                  java ControllersCleaner --controllers-file ./controllers.json --dry-run
                  java ControllersCleaner --controllers-file ./controllers.json
                """);
    }

    // ===== JSON-модели =====
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Root {
        public List<String> filter;
        public List<Controller> controller;
        public Root() {}
        public Root(List<String> filter, List<Controller> controller) { this.filter = filter; this.controller = controller; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Controller {
        public String chatid;
        public String link;         // link турнира (для матч-контроллеров может быть пуст)
        public String ruleFilter;   // у индекс-контроллера — НЕ пустой
        public String title;
        public List<Event> events = new ArrayList<>();
        public Controller() {}
        public Controller(String chatid, String link, String ruleFilter, String title, List<Event> events) {
            this.chatid = chatid; this.link = link; this.ruleFilter = ruleFilter; this.title = title;
            this.events = events != null ? events : new ArrayList<>();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Event {
        public String eventId;
        public String link;        // для индекса: link турнира; для матчей — link матча
        public String title;
        public String timestamp;   // для матчей есть, для индекса обычно нет
    }

    private record RemovedTournament(String file, String humanName, String matchKey) {}
}
