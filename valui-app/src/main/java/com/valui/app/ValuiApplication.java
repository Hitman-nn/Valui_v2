package com.valui.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(
    scanBasePackages = "com.valui",
    exclude = UserDetailsServiceAutoConfiguration.class
)
@ConfigurationPropertiesScan("com.valui")
public class ValuiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ValuiApplication.class, args);
    }
}
