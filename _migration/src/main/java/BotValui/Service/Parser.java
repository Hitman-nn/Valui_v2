package BotValui.Service;

import BotValui.components.Page;
import BotValui.config.ParserProxyConfig;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public interface Parser {
    enum SupportBK {xstavkaru, fonbet, olimpbet, betcity}

    static final int GZIP_BUF = 32 * 1024;
    static final int READ_BUF = 64 * 1024;
    // подберите под себя (16–32 МБ). Это "предохранитель" от слонов.
    static final long MAX_RESPONSE = 64L * 1024 * 1024;

    String BASE_URL_1XSTAVKA = "1xbet.kz";
    String BASE_URL_FONBET = "fon.bet";
    String BASE_URL_OLIMP = "olimp.bet";
    String BASE_URL_BETCITY = "betcity.ru";
    String BASE_URL_BETBOOM = "betboom.ru";
    int DEFAULT_HTTP_CONNECT_TIMEOUT = 10000;
    int DEFAULT_HTTP_READ_TIMEOUT = 10000;

    String EMPTY_LINE = "Данная линия отсутствует";
    String WARNING_ICON = "⚠️";

    Page getForControllerPage();

    Page getForControllerPage(String customTitle);

    static Object getJSONObject(String link, ParserProxyConfig proxyConfig) throws IOException, ParseException {
        return getJSONObject(link, proxyConfig, null);
    }

    static Object getJSONObject(String link, ParserProxyConfig proxyConfig, Map<String, String> formData)
            throws IOException, ParseException {

        HttpURLConnection connection = null;
        try {
            URL url = new URL(link);

            // Настройка подключения через прокси если нужно
            if (proxyConfig != null && proxyConfig.isProxyEnabled()) {
                Proxy proxy = new Proxy(Proxy.Type.HTTP,
                        new InetSocketAddress(proxyConfig.getProxyHost(), proxyConfig.getProxyPort()));
                connection = (HttpURLConnection) url.openConnection(proxy);

                // Установка заголовка авторизации ДО подключения
                String auth = proxyConfig.getProxyUser() + ":" + proxyConfig.getProxyPass();
                String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
                connection.setRequestProperty("Proxy-Authorization", "Basic " + encodedAuth);
            } else {
                connection = (HttpURLConnection) url.openConnection();
            }

            // Установка общих свойств соединения
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "gzip");
            connection.setRequestProperty("Connection", "keep-alive");
            //connection.setRequestProperty("Connection", "close");
            connection.setConnectTimeout(DEFAULT_HTTP_CONNECT_TIMEOUT);
            connection.setReadTimeout(DEFAULT_HTTP_READ_TIMEOUT);

            // Настройка метода запроса
            if (formData != null && !formData.isEmpty()) {
                return handlePostRequest(connection, formData);
            } else {
                return handleGetRequest(connection);
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static Object handleGetRequest(HttpURLConnection connection) throws IOException, ParseException {
        connection.setRequestMethod("GET");
        // Все свойства уже установлены в getJSONObject
        return processResponse(connection);
    }

    private static Object handlePostRequest(HttpURLConnection connection, Map<String, String> formData)
            throws IOException, ParseException {

        connection.setRequestMethod("POST");
        String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setDoOutput(true);

        try (OutputStream outputStream = connection.getOutputStream();
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8), true)) {

            for (Map.Entry<String, String> entry : formData.entrySet()) {
                writer.append("--").append(boundary).append("\r\n");
                writer.append("Content-Disposition: form-data; name=\"")
                        .append(entry.getKey()).append("\"\r\n\r\n");
                writer.append(entry.getValue()).append("\r\n");
            }
            writer.append("--").append(boundary).append("--").append("\r\n");
        }

        return processResponse(connection);
    }

    //    private static Object processResponse(HttpURLConnection connection) throws IOException, ParseException {
//        // Все свойства уже установлены, просто читаем ответ
//        try (InputStream inputStream = "gzip".equalsIgnoreCase(connection.getContentEncoding())
//                ? new GZIPInputStream(connection.getInputStream())
//                : connection.getInputStream();
//             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
//
//            StringBuilder response = new StringBuilder();
//            String line;
//            while ((line = reader.readLine()) != null) {
//                response.append(line);
//            }
//
//            return new JSONParser().parse(response.toString());
//        }
//    }
    private static Object processResponse(HttpURLConnection connection) throws IOException, ParseException {
        int code = connection.getResponseCode();

        InputStream raw = (code >= 400) ? connection.getErrorStream() : connection.getInputStream();
        if (raw == null) throw new IOException("No response stream (HTTP " + code + ")");

        InputStream in = "gzip".equalsIgnoreCase(connection.getContentEncoding())
                ? new GZIPInputStream(raw, GZIP_BUF)
                : raw;

        // Ограничиваем размер
        try (InputStream limited = new BoundedInputStream(in, MAX_RESPONSE)) {

            Charset cs = extractCharsetOrUtf8(connection.getHeaderField("Content-Type"));

            // Если ошибка — читаем небольшой кусок текста и бросаем исключение
            if (code >= 400) {
                String snippet = readSnippet(limited, cs, 4096);
                throw new ApiRequestException("HTTP " + code + " from " + connection.getURL(), code, snippet);
            }

            // Успех — парсим JSON из Reader
            try (InputStreamReader isr = new InputStreamReader(limited, cs);
                 BufferedReader reader = new BufferedReader(isr, READ_BUF)) {
                return new JSONParser().parse(reader);
            }
        }
    }

    private static String readSnippet(InputStream in, Charset cs, int maxChars) throws IOException {
        try (InputStreamReader isr = new InputStreamReader(in, cs);
             BufferedReader br = new BufferedReader(isr)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[512];
            int n;
            while ((n = br.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (sb.length() >= maxChars) break;
            }
            return sb.toString();
        }
    }

    final class BoundedInputStream extends InputStream {
        private final InputStream in;
        private final long max;
        private long read;

        BoundedInputStream(InputStream in, long max) {
            this.in = in;
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b != -1 && ++read > max) throw new IOException("Response too large");
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                read += n;
                if (read > max) throw new IOException("Response too large");
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    static Charset extractCharsetOrUtf8(String ct) {
        if (ct != null) {
            for (String part : ct.split(";")) {
                part = part.trim();
                if (part.toLowerCase().startsWith("charset=")) {
                    try {
                        return java.nio.charset.Charset.forName(part.substring(8).trim());
                    } catch (Exception ignore) {
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    default boolean isTitleValid(String title) {
        return title != null &&
                !title.isEmpty() &&
                !title.contains(EMPTY_LINE);
    }
}
