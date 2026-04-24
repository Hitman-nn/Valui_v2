package BotValui.testline;

import java.util.regex.Pattern;

public class regex {
    public static void main(String[] args){
        String regex = ".*(фифа|уефа|конкакаф|concacaf|азия|европ|товарищ|чемпионат.*мира|юношес|молод[её]ж|наци|мориса|кубо).*";
        String text1 = "Чемпионат Испании. Молодeёжная лига";
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        System.out.println("Тест 1: " + pattern.matcher(text1).matches()); // true



        //regex = "^(?!.*(?:дополнительные ставки|итоги турнира|пары)).*\\b(?:ATP|WTA)\\b.*$";
        regex = "^(?!.*\\b(пар\\w*|итог\\w*|итоги турнира|дополнительные ставки|челленджер|challenger)\\b).*\\b(atp|wta|Ролан Гаррос|Wimbledon|Уимблдон\\w*|US Open|ЮС Опен|УС Опен|Хопман\\w*)\\b.*";
        pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
        // Тест 1 - содержит "итоги турнира" → должно быть false
        String text2 = "Мадрид итоги турнира WTA Грунт";
        System.out.println("Тест 2: " + pattern.matcher(text2).matches()); // false
        // Тест 2 - не содержит запрещенных фраз → true
        String text3 = "Мадрид WTa. Грунт";
        System.out.println("Тест 3: " + pattern.matcher(text3).matches()); // true
        // Тест 3 - содержит "пары" → false
        String text4 = "ATP пар игроков";
        System.out.println("Тест 4: " + pattern.matcher(text4).matches()); // false
        String text5 = "WTA. Берлин. Квалификация. Трава";
        System.out.println("Тест 5: " + pattern.matcher(text5).matches()); // false
        String text6 = "Кубок Хуопман";
        System.out.println("Тест 6: " + pattern.matcher(text6).matches()); // true
    }

}