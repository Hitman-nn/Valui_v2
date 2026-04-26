package com.valui.parser.bookmaker.betcity;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class BetcitySportsMap {

    private static final Map<Integer, String> ZIP_SPORT = new HashMap<>();

    static {
        ZIP_SPORT.put(1, "soccer");
        ZIP_SPORT.put(2, "tennis");
        ZIP_SPORT.put(3, "basketball");
        ZIP_SPORT.put(4, "formula-1");
        ZIP_SPORT.put(5, "baseball");
        ZIP_SPORT.put(6, "american-football");
        ZIP_SPORT.put(7, "ice-hockey");
        ZIP_SPORT.put(8, "handball");
        ZIP_SPORT.put(9, "golf");
        ZIP_SPORT.put(10, "chess");
        ZIP_SPORT.put(11, "motorcycling");
        ZIP_SPORT.put(12, "volleyball");
        ZIP_SPORT.put(13, "rugby");
        ZIP_SPORT.put(14, "athletics");
        ZIP_SPORT.put(15, "biathlon");
        ZIP_SPORT.put(16, "cross-country-skiing");
        ZIP_SPORT.put(17, "bandy");
        ZIP_SPORT.put(18, "auto-racing");
        ZIP_SPORT.put(19, "futsal");
        ZIP_SPORT.put(20, "boxing");
        ZIP_SPORT.put(21, "cycling");
        ZIP_SPORT.put(22, "forex");
        ZIP_SPORT.put(23, "snooker");
        ZIP_SPORT.put(24, "water-polo");
        ZIP_SPORT.put(25, "lottery");
        ZIP_SPORT.put(26, "curling");
        ZIP_SPORT.put(33, "politics");
        ZIP_SPORT.put(34, "weightlifting");
        ZIP_SPORT.put(35, "olympics");
        ZIP_SPORT.put(36, "field-hockey");
        ZIP_SPORT.put(37, "aquatics");
        ZIP_SPORT.put(38, "culture");
        ZIP_SPORT.put(39, "ufc");
        ZIP_SPORT.put(40, "fencing");
        ZIP_SPORT.put(41, "rowing");
        ZIP_SPORT.put(42, "gymnastics");
        ZIP_SPORT.put(43, "beach-soccer");
        ZIP_SPORT.put(44, "speed-skating");
        ZIP_SPORT.put(45, "luge");
        ZIP_SPORT.put(46, "table-tennis");
        ZIP_SPORT.put(47, "darts");
        ZIP_SPORT.put(48, "lacrosse");
        ZIP_SPORT.put(49, "badminton");
        ZIP_SPORT.put(50, "judo");
        ZIP_SPORT.put(51, "equestrian");
        ZIP_SPORT.put(52, "sailing");
        ZIP_SPORT.put(53, "modern-pentathlon");
        ZIP_SPORT.put(54, "triathlon");
        ZIP_SPORT.put(55, "shooting");
        ZIP_SPORT.put(56, "taekwondo");
        ZIP_SPORT.put(57, "floorball");
        ZIP_SPORT.put(58, "beach-volleyball");
        ZIP_SPORT.put(59, "poker");
        ZIP_SPORT.put(60, "squash");
        ZIP_SPORT.put(61, "figure-skating");
        ZIP_SPORT.put(62, "short-track-speed-skating");
        ZIP_SPORT.put(63, "ski-jumping");
        ZIP_SPORT.put(64, "nordic-combined");
        ZIP_SPORT.put(65, "alpine-skiing");
        ZIP_SPORT.put(66, "freestyle-skiing");
        ZIP_SPORT.put(67, "snowboarding");
        ZIP_SPORT.put(68, "skeleton");
        ZIP_SPORT.put(69, "bobsleigh");
        ZIP_SPORT.put(70, "beach-handball");
        ZIP_SPORT.put(71, "australian-football");
        ZIP_SPORT.put(72, "paddle-tennis");
        ZIP_SPORT.put(73, "cybersport");
        ZIP_SPORT.put(74, "cricket");
        ZIP_SPORT.put(75, "bowls");
        ZIP_SPORT.put(76, "wrestling");
        ZIP_SPORT.put(77, "karate");
        ZIP_SPORT.put(78, "surfing");
        ZIP_SPORT.put(79, "climbing");
        ZIP_SPORT.put(80, "skateboarding");
        ZIP_SPORT.put(83, "breaking");
        ZIP_SPORT.put(84, "bowling");
        ZIP_SPORT.put(85, "gaelicsport");
        ZIP_SPORT.put(86, "sport");
    }

    private static final Map<String, Integer> SPORT_ZIP = ZIP_SPORT.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey, (a, b) -> a));

    private BetcitySportsMap() {}

    public static Optional<Integer> getSportId(String sportName) {
        return Optional.ofNullable(SPORT_ZIP.get(sportName));
    }

    public static Optional<String> getSport(Integer sportId) {
        return Optional.ofNullable(ZIP_SPORT.get(sportId));
    }
}
