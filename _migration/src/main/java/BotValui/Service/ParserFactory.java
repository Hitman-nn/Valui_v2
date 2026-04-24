package BotValui.Service;

import BotValui.Service.betboom.ParserBetboomAPI;
import BotValui.Service.betcity.ParserBetcityAPI;
import BotValui.Service.fonbet.ParserFonBetAPI;
import BotValui.Service.olimp.ParserOlimpAPI;
import BotValui.Service.xstavka.Parser1XStavkaAPI;
import lombok.extern.slf4j.Slf4j;

import java.net.MalformedURLException;

@Slf4j
public class ParserFactory {
    public static Parser createParser(String link) throws MalformedURLException {
        if (link.contains(Parser.BASE_URL_1XSTAVKA)) {
            return new Parser1XStavkaAPI(link);
        } else if (link.contains(Parser.BASE_URL_FONBET)) {
            return new ParserFonBetAPI(link);
        } else if (link.contains(Parser.BASE_URL_OLIMP)) {
            return new ParserOlimpAPI(link);
        } else if (link.contains(Parser.BASE_URL_BETCITY)) {
            return new ParserBetcityAPI(link);
        } else if (link.contains(Parser.BASE_URL_BETBOOM)) {
            return new ParserBetboomAPI(link);
        }
        log.warn("Error create controller. Not support site: " + link);
        throw new UnsupportedOperationException("Not supported site: " + link);
    }
}
