package com.valui.app.schema;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code kafka.schema-registry.*} from application.yml.
 *
 * Enabled by {@code @EnableConfigurationProperties(SchemaRegistryProperties.class)}
 * in {@link SchemaRegistrationService}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "kafka.schema-registry")
public class SchemaRegistryProperties {

    /** Schema Registry URL, e.g. http://localhost:8081. Empty string = SR disabled. */
    private String url = "";

    /** HTTP connect timeout in millis for SR REST calls. */
    private int connectTimeoutMs = 3_000;

    /** Max cached schema versions per subject. */
    private int cacheCapacity = 100;

    public boolean isEnabled() {
        return url != null && !url.isBlank();
    }
}
