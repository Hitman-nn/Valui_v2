package com.valui.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(
    scanBasePackages = "com.valui",
    exclude = UserDetailsServiceAutoConfiguration.class
)
public class ValuiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ValuiApplication.class, args);
    }
}
