package com.valui.app.alert;

import com.valui.notify.service.AdminNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Sends a Telegram alert to the admin chat on every application startup — deliberately
 * unconditional on *why* the process restarted, rather than trying to distinguish a manual
 * {@code systemctl restart} from one triggered by {@code valui-healthcheck.sh}: the app has no
 * reliable way to learn its own shutdown cause (SIGTERM carries no reason), and firing on every
 * {@link ApplicationReadyEvent} covers both triggers uniformly for free, which is what was
 * actually asked for — "notify admin about every restart, manual or scripted".
 */
@Component
@RequiredArgsConstructor
public class RestartNotifier {

    private final AdminNotificationService adminNotificationService;
    private final Environment env;
    private final ObjectProvider<BuildProperties> buildProperties;
    private final ObjectProvider<GitProperties> gitProperties;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady(ApplicationReadyEvent event) {
        BuildProperties build = buildProperties.getIfAvailable();
        GitProperties git = gitProperties.getIfAvailable();

        String version = build != null ? "v" + build.getVersion() : "—";
        String commit = git != null ? git.getShortCommitId() : null;
        String dirty = git != null && "true".equals(git.get("dirty")) ? "-dirty" : "";
        String profile = String.join(", ", env.getActiveProfiles());
        String startedIn = event.getTimeTaken() != null
                ? event.getTimeTaken().toSeconds() + "." + String.format("%03d", event.getTimeTaken().toMillisPart()) + "s"
                : "—";

        StringBuilder buildLine = new StringBuilder(version);
        if (commit != null) buildLine.append(" ").append(commit).append(dirty);

        adminNotificationService.alertAdmin(
                "🔄 *Приложение перезапущено*\n"
                + "Build: `" + buildLine + "`\n"
                + "Профиль: `" + profile + "`\n"
                + "Запуск занял: " + startedIn);
    }
}
