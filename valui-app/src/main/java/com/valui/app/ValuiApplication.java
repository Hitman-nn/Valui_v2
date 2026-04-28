package com.valui.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.valui")
public class ValuiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ValuiApplication.class, args);
    }
}
