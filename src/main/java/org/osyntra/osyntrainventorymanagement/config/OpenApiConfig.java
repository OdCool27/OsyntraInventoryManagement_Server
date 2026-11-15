package org.osyntra.osyntrainventorymanagement.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(title = "Orvexa API — Osyntra", version = "0.0.1",
        description = "Orvexa inventory and simulated POS backend by Osyntra. SQLite demonstration; see ORVEXA_B0_ASSESSMENT.md for the staged implementation plan."),
        security = @SecurityRequirement(name = "bearerAuth"))
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "Opaque revocable session token")
public class OpenApiConfig {
}
