package com.valui.app.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REGRESSION 02.10: the resilience4j block lived only in valui-parser's own application.yml,
 * which Spring never reads (the app module's classpath:application.yml shadows it), so prod
 * silently ran on Resilience4j defaults (100-call window, 10 HALF_OPEN trial calls). This pins
 * the config to the file that is actually loaded.
 */
@DisplayName("resilience4j config is in the application.yml Spring actually loads")
class ResilienceConfigLocationTest {

    @Test
    void circuitBreakerAndRetryConfigPresentInAppYaml() throws Exception {
        List<PropertySource<?>> docs = new YamlPropertySourceLoader()
                .load("app", new ClassPathResource("application.yml"));
        PropertySource<?> base = docs.get(0); // the profile-less document

        assertThat(base.getProperty("resilience4j.circuitbreaker.configs.parser-default.permitted-number-of-calls-in-half-open-state"))
                .hasToString("3");
        assertThat(base.getProperty("resilience4j.circuitbreaker.instances.xbet-cb.sliding-window-size"))
                .hasToString("6");
        assertThat(base.getProperty("resilience4j.circuitbreaker.instances.olimp-cb.base-config"))
                .hasToString("parser-default");
        assertThat(base.getProperty("resilience4j.retry.instances.parser-retry.max-attempts"))
                .hasToString("2");
    }
}
