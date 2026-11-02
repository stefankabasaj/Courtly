package com.stefankabasaj.courtly.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// the spec is generated from the controllers and DTOs, so it can't drift from the code.
// /v3/api-docs is the JSON (what a frontend generator or v0 should get), /swagger-ui.html the UI
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearer-jwt";

    @Bean
    public OpenAPI courtlyOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Courtly API")
                        .version("1.0.0")
                        .description("""
                                Tennis court booking. Log in with POST /api/v1/auth/login and send the token as \
                                `Authorization: Bearer <token>`.

                                Times are the courts' local time without an offset, e.g. 2026-11-05T10:00:00. \
                                Every error has the same JSON shape: status, error, message, path, timestamp, \
                                and fieldErrors for validation failures (422). Fields that are null are left out."""))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                // every endpoint except auth and health needs the token; Swagger UI shows a lock on them
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
