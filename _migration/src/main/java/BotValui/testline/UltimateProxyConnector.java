package BotValui.testline;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class UltimateProxyConnector {

    public static void main(String[] args) {
        // Конфигурация прокси (проверьте эти данные!)
        String proxyHost = "212.116.242.56";
        int proxyPort = 4232;
        String proxyUser = "user283146";
        String proxyPass = "0w3qzb";

        // Целевой URL
        String targetUrl = "https://1xbet.kz/service-api/LineFeed/GetChampsZip";

        // 1. Настройка системы перед выполнением запроса
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        System.setProperty("jdk.http.auth.proxying.disabledSchemes", "");
//        System.setProperty("https.proxyHost", proxyHost);
//        System.setProperty("https.proxyPort", String.valueOf(proxyPort));
//        System.setProperty("https.proxyUser", proxyUser);
//        System.setProperty("https.proxyPassword", proxyPass);

        try {
            // 3. Создание подключения через прокси
            Proxy proxy = new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(proxyHost, proxyPort));

            URL url = new URL(targetUrl);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection(proxy);

            // 4. Настройка соединения
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            // 5. Аутентификация (2 уровня)
            // Уровень 1: Системные свойства (уже установлены)
            // Уровень 2: Authenticator
            Authenticator.setDefault(new Authenticator() {
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(proxyUser, proxyPass.toCharArray());
                }
            });

            // 6. Выполнение запроса
            System.out.println("Выполняю запрос через прокси...");
            int responseCode = conn.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) {
                System.out.println("Успешное подключение!");
                readResponse(conn.getInputStream());
            } else {
                System.err.println("Ошибка подключения. Код: " + responseCode);
                if (conn.getErrorStream() != null) {
                    readResponse(conn.getErrorStream());
                } else {
                    System.err.println("Дополнительная информация: " + conn.getResponseMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Критическая ошибка:");
            e.printStackTrace();
        }
    }

    private static void readResponse(InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }
    }
}
