package com.codereviewer.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The description of the API served at {@code /v3/api-docs} and shown at
 * {@code /swagger-ui.html}. Paths, bodies and answers are read from the controllers; this
 * adds the title and the bearer token, which every operation needs unless it says otherwise.
 */
@Configuration
public class OpenApiConfig {

	/** Name of the security scheme; operations that need no token clear it. */
	public static final String BEARER_AUTH = "bearerAuth";

	@Bean
	OpenAPI openApi() {
		return new OpenAPI()
				.info(new Info().title("Second Pair API")
						.description("Code review of public GitHub repositories. Log in, then use Authorize "
								+ "with the accessToken from the answer.")
						.version("1.0.0"))
				.components(new Components().addSecuritySchemes(BEARER_AUTH,
						new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
				.addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
	}

}
