package com.valui.parser.http;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "parser.proxy")
public class ProxyProperties {
    private boolean enabled = false;
    private String host;
    private int port;
    private String username;
    private String password;
}
