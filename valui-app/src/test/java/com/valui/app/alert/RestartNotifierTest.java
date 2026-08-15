package com.valui.app.alert;

import com.valui.notify.service.AdminNotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.Properties;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("RestartNotifier — alerts admin on every startup, regardless of restart cause")
class RestartNotifierTest {

    @Mock AdminNotificationService adminNotificationService;
    @Mock ObjectProvider<BuildProperties> buildProperties;
    @Mock ObjectProvider<GitProperties> gitProperties;
    @Mock ApplicationReadyEvent event;

    @Test
    @DisplayName("Sends one alert mentioning the active profile — fires unconditionally, no restart-cause detection needed")
    void onReady_alertsAdminWithProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        given(buildProperties.getIfAvailable()).willReturn(null);
        given(gitProperties.getIfAvailable()).willReturn(null);
        given(event.getTimeTaken()).willReturn(Duration.ofSeconds(30));

        RestartNotifier notifier = new RestartNotifier(adminNotificationService, env, buildProperties, gitProperties);
        notifier.onReady(event);

        verify(adminNotificationService).alertAdmin(contains("prod"));
    }

    @Test
    @DisplayName("Includes build version and commit when available")
    void onReady_includesBuildInfo() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        BuildProperties build = new BuildProperties(propsWith("version", "2.0.0-SNAPSHOT"));
        GitProperties git = new GitProperties(propsWith("commit.id.abbrev", "993eeda", "dirty", "true"));
        given(buildProperties.getIfAvailable()).willReturn(build);
        given(gitProperties.getIfAvailable()).willReturn(git);
        given(event.getTimeTaken()).willReturn(Duration.ofSeconds(30));

        RestartNotifier notifier = new RestartNotifier(adminNotificationService, env, buildProperties, gitProperties);
        notifier.onReady(event);

        verify(adminNotificationService).alertAdmin(contains("993eeda-dirty"));
    }

    private static Properties propsWith(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i < kv.length; i += 2) p.setProperty(kv[i], kv[i + 1]);
        return p;
    }
}
