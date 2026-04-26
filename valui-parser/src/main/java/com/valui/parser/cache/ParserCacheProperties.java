package com.valui.parser.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "parser.cache")
public class ParserCacheProperties {
    private Duration sportsTtl    = Duration.ofHours(1);
    private Duration tournamentsTtl = Duration.ofMinutes(30);
    private Duration matchesTtl   = Duration.ofMinutes(5);
}
