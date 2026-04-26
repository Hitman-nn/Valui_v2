package com.valui.parser.bookmaker.betboom;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class BetBoomSportsMap {

    private static final Map<Integer, String> ZIP_SPORT = new HashMap<>();

    static {
        ZIP_SPORT.put(2, "football");
        ZIP_SPORT.put(3, "water-polo");
        ZIP_SPORT.put(4, "tennis");
        ZIP_SPORT.put(5, "basketball");
        ZIP_SPORT.put(6, "baseball");
        ZIP_SPORT.put(7, "american-football");
        ZIP_SPORT.put(8, "boxing");
        ZIP_SPORT.put(9, "golf");
        ZIP_SPORT.put(10, "chess");
        ZIP_SPORT.put(11, "ice-hockey");
        ZIP_SPORT.put(13, "volleyball");
        ZIP_SPORT.put(14, "handball");
        ZIP_SPORT.put(16, "biathlon");
        ZIP_SPORT.put(18, "futsal");
        ZIP_SPORT.put(19, "formula-1");
        ZIP_SPORT.put(20, "snooker");
        ZIP_SPORT.put(21, "darts");
        ZIP_SPORT.put(26, "table-tennis");
        ZIP_SPORT.put(27, "beach-volleyball");
        ZIP_SPORT.put(30, "badminton");
        ZIP_SPORT.put(31, "rugby-league");
        ZIP_SPORT.put(36, "cricket");
        ZIP_SPORT.put(37, "floorball");
        ZIP_SPORT.put(43, "ski-jumping");
        ZIP_SPORT.put(46, "martial-arts");
        ZIP_SPORT.put(85, "e-football");
        ZIP_SPORT.put(100, "wrestling");
        ZIP_SPORT.put(102, "auto-racing");
        ZIP_SPORT.put(105, "sumo");
        ZIP_SPORT.put(108, "bowling");
        ZIP_SPORT.put(110, "curling");
        ZIP_SPORT.put(121, "motorcycling");
        ZIP_SPORT.put(181, "squash");
    }

    private static final Map<String, Integer> SPORT_ZIP = ZIP_SPORT.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey, (a, b) -> a));

    private BetBoomSportsMap() {}

    public static Optional<Integer> getSportId(String alias) {
        return Optional.ofNullable(SPORT_ZIP.get(alias));
    }

    public static Optional<String> getSport(Integer id) {
        return Optional.ofNullable(ZIP_SPORT.get(id));
    }
}
