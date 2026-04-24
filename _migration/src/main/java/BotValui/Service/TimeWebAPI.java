package BotValui.Service;

import BotValui.Commands.Commands;
import BotValui.TelegramBotConnect.TelegramBot;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.NumberFormat;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TimeWebAPI {
    // Константы для API
    private static final String API_URL = "https://api.timeweb.cloud/api/v1/account/finances";
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    // Константы для форматирования
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withLocale(new Locale("ru"));
    private static final DateTimeFormatter DATE_ONLY_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy").withLocale(new Locale("ru"));
    private static final NumberFormat NUMBER_FORMAT =
            NumberFormat.getNumberInstance(new Locale("ru", "RU"));

    // Константы для уведомлений
    private static final DayOfWeek WEEKLY_NOTIFICATION_DAY = DayOfWeek.MONDAY;
    private static final int WEEKLY_NOTIFICATION_HOUR = 9;
    private static final int WEEKLY_NOTIFICATION_MINUTE = 0;
    private static final long DAILY_NOTIFICATION_HOURS = 24;
    private static final long HOURLY_NOTIFICATION_HOURS = 1;
    private static final int WARNING_DAYS_BEFORE = 7;
    private static final int CRITICAL_HOURS_BEFORE = 24;

    private final TelegramBot telegramBot;
    private final ThreadPoolManager threadPoolManager = ThreadPoolManager.getInstance();
    private ScheduledFuture<?> currentNotificationTask;

    @PostConstruct
    public void init() {
        scheduleFinanceNotifications();
    }

    private void scheduleFinanceNotifications() {
        // Отменяем предыдущую задачу, если она была
        if (currentNotificationTask != null) {
            currentNotificationTask.cancel(false);
        }

        // Получаем текущий баланс
        try {
            String jsonResponse = getFinancesAccount();
            JSONObject finances = new JSONObject(jsonResponse).getJSONObject("finances");
            int hoursLeft = finances.getInt("hours_left");

            // Определяем интервал уведомлений в зависимости от оставшегося времени
            if (hoursLeft <= CRITICAL_HOURS_BEFORE) {
                // Критический уровень - уведомления каждый час
                scheduleRecurrentNotifications(HOURLY_NOTIFICATION_HOURS, TimeUnit.HOURS);
                log.warn("Scheduled HOURLY finance notifications (critical level)");
            } else if (hoursLeft <= WARNING_DAYS_BEFORE * 24) {
                // Предупреждение - уведомления каждый день
                scheduleRecurrentNotifications(DAILY_NOTIFICATION_HOURS, TimeUnit.HOURS);
                log.warn("Scheduled DAILY finance notifications (warning level)");
            } else {
                // Нормальный режим - уведомления раз в неделю
                scheduleWeeklyNotifications();
                log.info("Scheduled WEEKLY finance notifications (normal mode)");
            }
        } catch (Exception e) {
            log.error("Failed to schedule finance notifications", e);
            // В случае ошибки пробуем снова через час
            threadPoolManager.getExecutor().schedule(
                    this::scheduleFinanceNotifications,
                    1, TimeUnit.HOURS
            );
        }
    }

    private void scheduleWeeklyNotifications() {
        long initialDelay = calculateInitialDelay(
                WEEKLY_NOTIFICATION_DAY,
                WEEKLY_NOTIFICATION_HOUR,
                WEEKLY_NOTIFICATION_MINUTE
        );

        currentNotificationTask = threadPoolManager.getExecutor().scheduleAtFixedRate(
                this::sendFinanceNotificationsWithReschedule,
                initialDelay,
                7 * 24 * 60, // 1 неделя в минутах
                TimeUnit.MINUTES
        );

        LocalDateTime firstNotificationTime = LocalDateTime.now()
                .plusMinutes(initialDelay)
                .truncatedTo(ChronoUnit.MINUTES);

        log.info("Scheduled weekly finance notifications. First at {}",
                firstNotificationTime.format(DATE_FORMATTER));
    }

    private void scheduleRecurrentNotifications(long interval, TimeUnit unit) {
        currentNotificationTask = threadPoolManager.getExecutor().scheduleAtFixedRate(
                this::sendFinanceNotificationsWithReschedule,
                0, // начать немедленно
                interval,
                unit
        );
        log.info("Scheduled recurrent notifications every {} {}", interval, unit);
    }

    private void sendFinanceNotificationsWithReschedule() {
        sendFinanceNotificationsToAllChats();
        // После отправки проверяем, нужно ли изменить интервал уведомлений
        threadPoolManager.getExecutor().schedule(
                this::scheduleFinanceNotifications,
                1, TimeUnit.MINUTES
        );
    }

    private long calculateInitialDelay(DayOfWeek targetDay, int targetHour, int targetMinute) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextRun = now.with(TemporalAdjusters.nextOrSame(targetDay))
                .withHour(targetHour)
                .withMinute(targetMinute)
                .withSecond(0)
                .withNano(0);

        if (now.isAfter(nextRun)) {
            nextRun = nextRun.plusWeeks(1);
        }

        return Duration.between(now, nextRun).toMinutes()+1;
    }


    public void sendFinanceNotificationsToAllChats() {
        log.info("Starting finance notifications");
        try {
            String jsonResponse = getFinancesAccount();
            JSONObject json = new JSONObject(jsonResponse);
            JSONObject finances = json.getJSONObject("finances");

            String message = formatBalanceMessage(jsonResponse);
            int hoursLeft = finances.getInt("hours_left");

            // Добавляем предупреждение, если баланс заканчивается
            if (hoursLeft <= CRITICAL_HOURS_BEFORE) {
                message = "🚨 *КРИТИЧЕСКИЙ УРОВЕНЬ!* 🚨\n\n" + message +
                        "\n\n⚠️ Баланс закончится через " + hoursLeft + " часов!";
            } else if (hoursLeft <= WARNING_DAYS_BEFORE * 24) {
                int daysLeft = hoursLeft / 24;
                message = "⚠️ *ВНИМАНИЕ!* ⚠️\n\n" + message +
                        "\n\nБаланс закончится через ~" + daysLeft + " дней!";
            }

            String finalMessage = message;
            Commands.getControllerListByChatId().keySet().forEach(chatId -> {
                telegramBot.buildAndSendMessage(chatId, finalMessage);
                log.debug("Sent finance notification to chat {}", chatId);
            });
        } catch (Exception e) {
            log.error("Failed to send finance notifications", e);
        }
    }

    public String getFinancesAccount() throws IOException {
        String apiToken = System.getenv("TIMEWEB_API_TOKEN");
        if (apiToken == null || apiToken.isBlank()) {
            throw new IllegalStateException("TIMEWEB_API_TOKEN environment variable is not set");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header(AUTH_HEADER, BEARER_PREFIX + apiToken)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() / 100 != 2) {
                throw new IOException("API request failed with status: " + response.statusCode()
                        + ", body: " + response.body());
            }

            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request was interrupted", e);
        }
    }

    public String formatBalanceMessage(String jsonResponse) {
        JSONObject json = new JSONObject(jsonResponse);
        JSONObject finances = json.getJSONObject("finances");

        return String.format("""
                        💰 *Состояние баланса TimeWeb Сервера* 💰
                                    
                        ▪️ **Текущий баланс:** %s ₽
                        ▪️ **Скидка:** %s%% (действует до %s)
                                    
                        **Расходы:**
                        ├─ Почасовая: %s ₽/час
                        └─ Помесячная: %s ₽/мес
                                    
                        **Осталось часов работы:** %s (~ %s)
                        **Всего оплачено:** %s ₽
                        """,
                NUMBER_FORMAT.format(finances.getDouble("balance")),
                finances.getInt("discount_percent"),
                finances.getString("discount_end_date_at").split("T")[0],
                NUMBER_FORMAT.format(finances.getDouble("hourly_cost")),
                NUMBER_FORMAT.format(finances.getDouble("monthly_cost")),
                NUMBER_FORMAT.format(finances.getInt("hours_left")),
                calculateEndDate(finances.getInt("hours_left")),
                NUMBER_FORMAT.format(finances.getDouble("total_paid"))
        );
    }

    public static String calculateEndDate(int hoursLeft) {
        return LocalDateTime.now()
                .plusHours(hoursLeft)
                .atZone(ZoneId.systemDefault())
                .format(DATE_FORMATTER);
    }
}