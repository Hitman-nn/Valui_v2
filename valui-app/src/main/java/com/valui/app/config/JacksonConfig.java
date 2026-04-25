package com.valui.app.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    /**
     * Customizes the primary (auto-configured) ObjectMapper used by Spring MVC.
     * REST API responses and request bodies are serialized with snake_case.
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer restMapperCustomizer() {
        return builder -> builder
            .modules(new JavaTimeModule())
            .featuresToDisable(
                SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES
            )
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    /**
     * Secondary mapper with camelCase for internal use: Kafka messages,
     * inter-module serialization, caching. Inject via @Qualifier("internalObjectMapper").
     */
    @Bean
    @Qualifier("internalObjectMapper")
    public ObjectMapper internalObjectMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        // camelCase is Jackson default — no NamingStrategy set
    }
}
