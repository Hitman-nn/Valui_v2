package com.valui.admin.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Valui v2 API")
                        .version("1.0.0")
                        .description("""
                                REST API для управления контроллерами мониторинга и профилем пользователя.

                                **Аутентификация**: Bearer JWT (получить через `POST /api/v1/auth/token`).

                                **Версионирование**: поддерживается `Accept: application/vnd.valui.v1+json` \
                                и `Accept: application/json`.
                                """)
                        .contact(new Contact().name("Valui Team")))
                .servers(List.of(
                        new Server().url("/").description("Current")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH))
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT access token из /api/v1/auth/token")));
    }
}
