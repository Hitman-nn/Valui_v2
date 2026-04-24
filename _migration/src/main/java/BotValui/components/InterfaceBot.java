package BotValui.components;

import BotValui.Commands.Commands;
import BotValui.Service.Parser;
import BotValui.Service.xstavka.Parser1XStavkaAPI;
import BotValui.Service.betcity.ParserBetcityAPI;
import BotValui.Service.fonbet.ParserFonBetAPI;
import BotValui.Service.olimp.ParserOlimpAPI;
import BotValui.Service.betboom.ParserBetboomAPI;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class InterfaceBot {
    public static final String NAVIGATION_CONST = "menu";
    public static final String BK_CONST = "bk";
    public static final String CONTROLLER_ZIP_CONST = "controllerZip";
    public static final String SPORT_ZIP_CONST = "sportZip";
    public static final String CHAMP_ZIP_CONST = "champZip";
    private static final String ADDED_SOURCE_IMG = "✅";

    public enum MenuList {
        GENERAL, CONTROLLER, SELECTCONROLLER, FILTER, SELECTBK, DELETECONTROLLER, SELECTSPORT, SELECTCHAMP,
        DELETEFILTER, ADDFILTER, INPUT_RULES_SPORT
    }

    public static final LinkedHashMap<String, String> menu_general = new LinkedHashMap<>() {{
        put("Контроль объектов", NAVIGATION_CONST + "=" + MenuList.CONTROLLER);
        put("Фильтр", NAVIGATION_CONST + "=" + MenuList.FILTER);
        put("Помощь", "/help");
        put("Выключить бота", "/stop");
    }};
    public static final LinkedHashMap<String, String> menu_controller = new LinkedHashMap<>() {{
        put("Добавление", NAVIGATION_CONST + "=" + MenuList.SELECTCONROLLER);
        put("Удаление", NAVIGATION_CONST + "=" + MenuList.DELETECONTROLLER);
        put("Удаление всех", "/deleteall");
        put("Список контроля", "/list");
        put("Назад", NAVIGATION_CONST + "=" + MenuList.GENERAL);
        put("Главное меню", NAVIGATION_CONST + "=" + MenuList.GENERAL);
    }};
    public static final LinkedHashMap<String, String> menu_selectBK = new LinkedHashMap<>() {{
        put("Назад", NAVIGATION_CONST + "=" + MenuList.SELECTCONROLLER);
        put("Главное меню", NAVIGATION_CONST + "=" + MenuList.GENERAL);
    }};
    public static LinkedHashMap<String, String> menu_selectSport = new LinkedHashMap<>() {{
        //put("Назад", NAVIGATION_CONST + "="+ MenuList.SELECTBK);
        put("Главное меню", NAVIGATION_CONST + "=" + MenuList.GENERAL);
    }};
    public static LinkedHashMap<String, String> menu_selectChamp = new LinkedHashMap<>() {{
        put("Назад", NAVIGATION_CONST + "=" + MenuList.SELECTBK + "&" + CONTROLLER_ZIP_CONST + "=" + CHAMP_ZIP_CONST);
        put("Главное меню", NAVIGATION_CONST + "=" + MenuList.GENERAL);
    }};

    public static final LinkedHashMap<String, String> menu_filter = new LinkedHashMap<>() {{
        put("Добавление", NAVIGATION_CONST + "=" + MenuList.ADDFILTER);
        put("Удаление", NAVIGATION_CONST + "=" + MenuList.DELETEFILTER);
        put("Удаление всех", "/deleteallfilter");
        put("Список фильтра", "/listfilter");
        put("Назад", NAVIGATION_CONST + "=" + MenuList.GENERAL);
        put("Главное меню", NAVIGATION_CONST + "=" + MenuList.GENERAL);
    }};
    public static final LinkedHashMap<String, String> menu_deleteFilter = new LinkedHashMap<>() {{
        put("Назад", NAVIGATION_CONST + "=" + MenuList.FILTER);
        put("Главное меню", NAVIGATION_CONST + "=" + MenuList.GENERAL);
    }};
    public static final LinkedHashMap<String, String> menu_deleteChamp = new LinkedHashMap<>() {{
        put("Назад", NAVIGATION_CONST + "=" + MenuList.CONTROLLER);
        put("Главное меню", NAVIGATION_CONST + "=" + MenuList.GENERAL);
    }};
    public static final LinkedHashMap<String, String> menu_selectControl = new LinkedHashMap<>() {{
        put("Контроль чемпионата", NAVIGATION_CONST + "=" + MenuList.SELECTBK + "&"
                + CONTROLLER_ZIP_CONST + "=" + CHAMP_ZIP_CONST);
        put("Контроль вида спорта", NAVIGATION_CONST + "=" + MenuList.SELECTBK + "&"
                + CONTROLLER_ZIP_CONST + "=" + SPORT_ZIP_CONST);
        put("Назад", NAVIGATION_CONST + "=" + MenuList.CONTROLLER);
        put("Главное меню", NAVIGATION_CONST + "=" + MenuList.GENERAL);
    }};
    public static final LinkedHashMap<String, String> menu_inputRulesSport = new LinkedHashMap<>() {{
        put("Назад", NAVIGATION_CONST + "=" + MenuList.SELECTBK + "&" + CONTROLLER_ZIP_CONST + "=" + SPORT_ZIP_CONST);
        put("Главное меню", NAVIGATION_CONST + "=" + MenuList.GENERAL);
    }};
    public static final LinkedHashMap<String, String> menu_cancel_input = new LinkedHashMap<>() {{
        put("Главное меню", NAVIGATION_CONST + "=" + MenuList.GENERAL);
    }};
    private static final TreeMap<MenuList, LinkedHashMap<String, String>> menuMap = new TreeMap<>() {{
        put(MenuList.CONTROLLER, menu_controller);
        put(MenuList.SELECTBK, menu_selectBK);
        put(MenuList.GENERAL, menu_general);
        put(MenuList.SELECTSPORT, menu_selectSport);
        put(MenuList.SELECTCHAMP, menu_selectChamp);
        put(MenuList.DELETECONTROLLER, menu_deleteChamp);
        put(MenuList.FILTER, menu_filter);
        put(MenuList.DELETEFILTER, menu_deleteFilter);
        put(MenuList.SELECTCONROLLER, menu_selectControl);
        put(MenuList.INPUT_RULES_SPORT, menu_inputRulesSport);
    }};

    public static LinkedList<Button> createButton(LinkedHashMap<String, String> buttons) {
        LinkedList<Button> buttonList = new LinkedList<>();
        for (Map.Entry entry : buttons.entrySet()) {
            buttonList.add(new Button(entry.getKey().toString(), entry.getValue().toString()));
        }
        return buttonList;
    }

    public static LinkedList<Button> createButton(String title, String data) {
        LinkedList<Button> buttonList = new LinkedList<>();
        buttonList.add(new Button(title, data));
        return buttonList;
    }

    public static Menu buildMenu(long chatId, String messageText) {
        LinkedHashMap<String, String> buttonsMap = new LinkedHashMap<>();

        String navigationLine = parserButtonParam(messageText, NAVIGATION_CONST);
        String menuKey = navigationLine.split("/")[navigationLine.split("/").length - 1];
        if (menuKey.isEmpty()) return null;

        Menu menu = null;
        MenuList menuListEnum = MenuList.valueOf(menuKey.toUpperCase());
        switch (menuListEnum) {
            case DELETEFILTER -> {
                for (String filter : FilterEvent.getFilterList()) {
                    buttonsMap.put(filter, "/deletefilter " + filter);
                }
                buttonsMap.putAll(menuMap.get(menuListEnum));
                menu = new Menu("Выберите фильтр для удаления:", createButton(buttonsMap), 1);
            }
            case ADDFILTER -> {

            }
            case SELECTSPORT -> {
                String bk = parserButtonParam(messageText, BK_CONST);
                String controllerZip = parserButtonParam(messageText, CONTROLLER_ZIP_CONST);
                String startParam = "";
                String backButton = NAVIGATION_CONST + "=" + MenuList.SELECTBK + "&" + CONTROLLER_ZIP_CONST + "=" + controllerZip;
                if (controllerZip.equalsIgnoreCase(SPORT_ZIP_CONST)) {
                    startParam = NAVIGATION_CONST + "=" + MenuList.INPUT_RULES_SPORT;
                } else if (controllerZip.equalsIgnoreCase(CHAMP_ZIP_CONST)) {
                    startParam = NAVIGATION_CONST + "=" + MenuList.SELECTCHAMP;
                }
                if (bk.equalsIgnoreCase(Parser.BASE_URL_1XSTAVKA)) {
                    for (Map.Entry entry : Parser1XStavkaAPI.getAllSport().entrySet()) {
                        buttonsMap.put(entry.getKey().toString(),
                                startParam +
                                        "&" + BK_CONST + "=" + Parser.BASE_URL_1XSTAVKA +
                                        "&" + SPORT_ZIP_CONST + "=" + entry.getValue().toString());
                    }
                } else if (bk.equalsIgnoreCase(Parser.BASE_URL_OLIMP)) {
                    for (Map.Entry entry : ParserOlimpAPI.getAllSport().entrySet()) {
                        buttonsMap.put(entry.getKey().toString(),
                                startParam +
                                        "&" + BK_CONST + "=" + Parser.BASE_URL_OLIMP +
                                        "&" + SPORT_ZIP_CONST + "=" + entry.getValue().toString());
                    }
                } else if (bk.equalsIgnoreCase(Parser.BASE_URL_FONBET)) {
                    for (Map.Entry entry : ParserFonBetAPI.getAllSport().entrySet()) {
                        buttonsMap.put(entry.getKey().toString(),
                                startParam +
                                        "&" + BK_CONST + "=" + Parser.BASE_URL_FONBET +
                                        "&" + SPORT_ZIP_CONST + "=" + entry.getValue().toString());
                    }
                } else if (bk.equalsIgnoreCase(Parser.BASE_URL_BETCITY)) {
                    for (Map.Entry entry : ParserBetcityAPI.getAllSport().entrySet()) {
                        buttonsMap.put(entry.getKey().toString(),
                                startParam +
                                        "&" + BK_CONST + "=" + Parser.BASE_URL_BETCITY +
                                        "&" + SPORT_ZIP_CONST + "=" + entry.getValue().toString());
                    }
                } else if (bk.equalsIgnoreCase(Parser.BASE_URL_BETBOOM)) {
                    for (Map.Entry entry : ParserBetboomAPI.getAllSport().entrySet()) {
                        buttonsMap.put(entry.getKey().toString(),
                                startParam +
                                        "&" + BK_CONST + "=" + Parser.BASE_URL_BETBOOM +
                                        "&" + SPORT_ZIP_CONST + "=" + entry.getValue().toString());
                    }
                }
                buttonsMap.put("Назад", NAVIGATION_CONST + "=" + MenuList.SELECTBK + "&" + CONTROLLER_ZIP_CONST + "=" + controllerZip);
                buttonsMap.putAll(menuMap.get(menuListEnum));
                menu = new Menu("Выберите вид спорта:", createButton(buttonsMap), 2);
            }
            case SELECTCHAMP -> {
                String bk = parserButtonParam(messageText, BK_CONST);
                List<Controller> controllerList = Commands.getControllerListByChatId().get(chatId);
                Map<String, Controller> addedChamp = new HashMap<>();
                if (controllerList != null) {
                    addedChamp = controllerList.stream()
                            .filter(controller -> controller.getPage().getIdChamp() != null && !controller.getPage().getIdChamp().isBlank())
                            .collect(Collectors.toMap(
                                    controller -> controller.getPage().getIdChamp(),
                                    controller -> controller
                            ));
                }
                if (bk.equalsIgnoreCase(Parser.BASE_URL_1XSTAVKA)) {
                    for (Map.Entry entry : Parser1XStavkaAPI.getAllChampSport(parserButtonParam(messageText, SPORT_ZIP_CONST)).entrySet()) {
                        buttonsMap.put((addedChamp.get(entry.getValue()) != null ? ADDED_SOURCE_IMG : "") + entry.getKey(),
                                "/addidchamp " +
                                        BK_CONST + "=" + Parser.BASE_URL_1XSTAVKA +
                                        "&" + CHAMP_ZIP_CONST + "=" + entry.getValue().toString());
                    }
                }
                if (bk.equalsIgnoreCase(Parser.BASE_URL_OLIMP)) {
                    for (Map.Entry entry : ParserOlimpAPI.getAllChampSport(parserButtonParam(messageText, SPORT_ZIP_CONST)).entrySet()) {
                        buttonsMap.put((addedChamp.get(entry.getValue()) != null ? ADDED_SOURCE_IMG : "") + entry.getKey(),
                                "/addidchamp " +
                                        BK_CONST + "=" + Parser.BASE_URL_OLIMP +
                                        "&" + CHAMP_ZIP_CONST + "=" + entry.getValue().toString());
                    }
                }
                if (bk.equalsIgnoreCase(Parser.BASE_URL_FONBET)) {
                    for (Map.Entry entry : ParserFonBetAPI.getAllChampSport(parserButtonParam(messageText, SPORT_ZIP_CONST)).entrySet()) {
                        buttonsMap.put((addedChamp.get(entry.getValue()) != null ? ADDED_SOURCE_IMG : "") + entry.getKey(),
                                "/addidchamp " +
                                        BK_CONST + "=" + Parser.BASE_URL_FONBET +
                                        "&" + CHAMP_ZIP_CONST + "=" + entry.getValue().toString());
                    }
                }
                if (bk.equalsIgnoreCase(Parser.BASE_URL_BETCITY)) {
                    for (Map.Entry entry : ParserBetcityAPI.getAllChampSport(parserButtonParam(messageText, SPORT_ZIP_CONST)).entrySet()) {
                        buttonsMap.put((addedChamp.get(entry.getValue()) != null ? ADDED_SOURCE_IMG : "") + entry.getKey(),
                                "/addidchamp " +
                                        BK_CONST + "=" + Parser.BASE_URL_BETCITY +
                                        "&" + CHAMP_ZIP_CONST + "=" + entry.getValue().toString());
                    }
                }
                if (bk.equalsIgnoreCase(Parser.BASE_URL_BETBOOM)) {
                    for (Map.Entry entry : ParserBetboomAPI.getAllChampSport(parserButtonParam(messageText, SPORT_ZIP_CONST)).entrySet()) {
                        buttonsMap.put((addedChamp.get(entry.getValue()) != null ? ADDED_SOURCE_IMG : "") + entry.getKey(),
                                "/addidchamp " +
                                        BK_CONST + "=" + Parser.BASE_URL_BETBOOM +
                                        "&" + CHAMP_ZIP_CONST + "=" + entry.getValue().toString());
                    }
                }
                buttonsMap.putAll(menuMap.get(menuListEnum));
                menu = new Menu("Выберите чемпионат для контроля:", createButton(buttonsMap), 1);
            }
            case DELETECONTROLLER -> {
                for (Controller controller : Commands.getAllControllerByChatId(chatId)) {
                    buttonsMap.put(controller.getPage().getTitle(), controller.getPage().getIdChamp().isEmpty() ?
                            "/deletesportlink " + controller.getPage().getLink() : "/deleteidchamp " + controller.getPage().getIdChamp());
                    //buttonsMap.put(controller.getPage().getTitle(), "/deleteidchamp " + controller.getPage().getIdChamp());
                }
                buttonsMap.putAll(menuMap.get(menuListEnum));
                menu = new Menu("Выберите чемпионат для снятия с контроля:", createButton(buttonsMap), 1);
            }
            case GENERAL -> {
                buttonsMap = menuMap.get(menuListEnum);
                menu = new Menu("Главное меню:", createButton(buttonsMap), 2);
            }
            case FILTER -> {
                buttonsMap = menuMap.get(menuListEnum);
                menu = new Menu("Меню фильтра:", createButton(buttonsMap), 2);
            }
            case CONTROLLER -> {
                buttonsMap = menuMap.get(menuListEnum);
                menu = new Menu("Меню объектов контроля:", createButton(buttonsMap), 2);
            }
            case SELECTBK -> {
                String controllerZip = parserButtonParam(messageText, CONTROLLER_ZIP_CONST);
                buttonsMap.put("1xstavka.ru", NAVIGATION_CONST + "=" + MenuList.SELECTSPORT +
                        "&" + CONTROLLER_ZIP_CONST + "=" + controllerZip +
                        "&" + BK_CONST + "=" + Parser.BASE_URL_1XSTAVKA);
                buttonsMap.put("fon.bet", NAVIGATION_CONST + "=" + MenuList.SELECTSPORT +
                        "&" + CONTROLLER_ZIP_CONST + "=" + controllerZip +
                        "&" + BK_CONST + "=" + Parser.BASE_URL_FONBET);
                buttonsMap.put("olimp.bet", NAVIGATION_CONST + "=" + MenuList.SELECTSPORT +
                        "&" + CONTROLLER_ZIP_CONST + "=" + controllerZip +
                        "&" + BK_CONST + "=" + Parser.BASE_URL_OLIMP);
                buttonsMap.put("betcity.ru", NAVIGATION_CONST + "=" + MenuList.SELECTSPORT +
                        "&" + CONTROLLER_ZIP_CONST + "=" + controllerZip +
                        "&" + BK_CONST + "=" + Parser.BASE_URL_BETCITY);
                buttonsMap.put("betboom.ru", NAVIGATION_CONST + "=" + MenuList.SELECTSPORT +
                        "&" + CONTROLLER_ZIP_CONST + "=" + controllerZip +
                        "&" + BK_CONST + "=" + Parser.BASE_URL_BETBOOM);
                buttonsMap.putAll(menuMap.get(menuListEnum));
                menu = new Menu("Выберите контору:", createButton(buttonsMap), 2);
            }
            case SELECTCONROLLER -> {
                buttonsMap = menuMap.get(menuListEnum);
                menu = new Menu("Выберите тип контроля:", createButton(buttonsMap), 2);
            }
            case INPUT_RULES_SPORT -> {
                String bk = parserButtonParam(messageText, BK_CONST);
                String sportZip = parserButtonParam(messageText, SPORT_ZIP_CONST);
                buttonsMap.put("Без правила", "/addidchamp &" +
                        BK_CONST + "=" + bk +
                        "&" + SPORT_ZIP_CONST + "=" + sportZip);
                buttonsMap.put("Ввести правило", "/inputrulesport &" +
                        BK_CONST + "=" + bk +
                        "&" + SPORT_ZIP_CONST + "=" + sportZip);
                buttonsMap.putAll(menuMap.get(menuListEnum));
                menu = new Menu("Выберите тип фильтра для мониторинга новых чемпионатов:",
                        createButton(buttonsMap), 2);
            }
            default -> {
                buttonsMap = menuMap.get(menuListEnum);
                menu = new Menu("Меню", createButton(buttonsMap), 1);
            }
        }
        return menu;
    }

    public static String parserButtonParam(String text, String paramNeed) {
        String[] paramList = text.split("&");
        for (String param : paramList) {
            String[] paramKeyValue = param.split("=");
            if (paramKeyValue[0].equalsIgnoreCase(paramNeed) && paramKeyValue.length > 1) return paramKeyValue[1];
        }
        return "";
    }
}
