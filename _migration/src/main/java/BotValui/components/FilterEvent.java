package BotValui.components;

import lombok.Getter;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

public class FilterEvent {
    @Getter
    private static TreeSet<String> filterList = new TreeSet<>(Arrays.asList("гости", "игроки", "хозяева"));

    public static void addFilter(String newWord) {
        if (newWord.isEmpty()) return;
        filterList.add(newWord);
    }

    public static void addFilters(Set<String> newWords) {
        if (newWords.isEmpty()) return;
        filterList.addAll(newWords);
    }

    public static void deleteFilter(String deleteWord) {
        if (deleteWord.isEmpty()) return;
        filterList.remove(deleteWord);
    }

    public static void deleteAllFilter() {
        filterList.clear();
    }

    public static boolean contains(String checkWord) {
        checkWord = checkWord.toLowerCase();
        for (String filter : filterList) {
            if (checkWord.contains(filter)) return true;
        }
        return false;
    }

}
