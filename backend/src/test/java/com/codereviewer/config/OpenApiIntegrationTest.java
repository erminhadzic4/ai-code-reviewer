package com.codereviewer.config;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.codereviewer.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class OpenApiIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void apiDescriptionIsPublicAndListsEveryEndpoint() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths.keys()").value(containsInAnyOrder("/api/auth/register",
						"/api/auth/login", "/api/auth/me", "/api/reviews", "/api/reviews/{id}",
						"/api/reviews/{id}/result")))
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
				.andExpect(jsonPath("$.security[0]", hasKey("bearerAuth")));
	}

	@Test
	void loginAndRegistrationNeedNoToken() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(jsonPath("$.paths['/api/auth/login'].post.security").value(empty()))
				.andExpect(jsonPath("$.paths['/api/auth/register'].post.security").value(empty()))
				// No security of its own: the global bearer requirement applies.
				.andExpect(jsonPath("$.paths['/api/reviews'].get", not(hasKey("security"))));
	}

	@Test
	void signedInUserIsNotAParameter() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(jsonPath("$.paths['/api/auth/me'].get", not(hasKey("parameters"))));
	}

	@Test
	void swaggerUiIsPublic() throws Exception {
		mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
	}

	@Test
	void documentedEndpointsStillNeedAToken() throws Exception {
		mockMvc.perform(get("/api/reviews")).andExpect(status().isUnauthorized());
	}

}
