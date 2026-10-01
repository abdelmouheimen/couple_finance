package com.couplefinance.shared.openapi;

import java.util.List;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI contract metadata. The generated spec is committed to {@code api/openapi.yaml} and checked for drift
 * by {@code OpenApiContractTest} (architecture.md §7).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI coupleFinanceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("CoupleFinance API")
                        .version("v1")
                        .description("REST API of the CoupleFinance mobile app. Errors use RFC 9457 Problem Details "
                                + "with a stable `code` property."))
                // A fixed relative server keeps the generated contract identical across environments.
                .servers(List.of(new Server().url("/").description("Current host")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
