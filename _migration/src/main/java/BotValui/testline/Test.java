package BotValui.testline;

import BotValui.Service.xstavka.Parser1XStavkaAPI;

import java.net.MalformedURLException;

public class Test {
    public static void main(String[] args) throws MalformedURLException {
//        ParserBetcityAPI p =new ParserBetcityAPI("https://betcity.ru/ru/line/soccer/337");
//        System.out.println(p.getEvents());
        Parser1XStavkaAPI.initProxyConfig();
        System.out.println(Parser1XStavkaAPI.getAllSport());
    }
}
