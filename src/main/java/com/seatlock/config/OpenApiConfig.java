package com.seatlock.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI seatLockOpenApi() {
        final String scheme = "bearerAuth";
        return new OpenAPI()
                .info(new Info()
                        .title("SeatLock API")
                        .version("1.0.0")
                        .description("""
                                Event ticketing API built so that a seat cannot be sold twice,
                                even under heavy concurrent load.

                                Booking is a two-step flow: POST a hold to reserve seats for a
                                few minutes, then POST a booking to convert that hold. Send an
                                `Idempotency-Key` header with the booking so a network retry
                                replays the original result instead of buying twice.
                                """))
                .addSecurityItem(new SecurityRequirement().addList(scheme))
                .components(new Components().addSecuritySchemes(scheme,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
