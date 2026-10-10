package com.codereviewer.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import javax.crypto.spec.SecretKeySpec;

import com.codereviewer.IntegrationTest;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class SecurityIntegrationTest {

	private static final String PROTECTED_PATH = "/api/auth/me";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtService jwtService;

	@Value("${app.jwt.secret}")
	private String secret;

	@Test
	void requestWithoutATokenIsRejectedWithAProblemDetail() throws Exception {
		expectUnauthorized(mockMvc.perform(get(PROTECTED_PATH)));
	}

	@Test
	void malformedTokenIsRejected() throws Exception {
		expectUnauthorized(mockMvc.perform(get(PROTECTED_PATH).header("Authorization", "Bearer not-a-token")));
	}

	@Test
	void expiredTokenIsRejected() throws Exception {
		String token = signedToken(secret, Instant.now().minusSeconds(60));

		expectUnauthorized(mockMvc.perform(get(PROTECTED_PATH).header("Authorization", "Bearer " + token)));
	}

	@Test
	void tokenSignedWithAnotherSecretIsRejected() throws Exception {
		String token = signedToken("another-secret-0123456789-abcdefghijkl", Instant.now().plusSeconds(3600));

		expectUnauthorized(mockMvc.perform(get(PROTECTED_PATH).header("Authorization", "Bearer " + token)));
	}

	@Test
	void unknownPathIsClosedByDefault() throws Exception {
		expectUnauthorized(mockMvc.perform(get("/api/does-not-exist")));
		expectUnauthorized(mockMvc.perform(get("/actuator/env")));
	}

	@Test
	void validTokenPassesTheAccessRules() throws Exception {
		// Nothing is mapped at this path, so getting 404 instead of 401 shows the token was accepted.
		mockMvc.perform(get("/api/does-not-exist").header("Authorization", "Bearer " + jwtService.issue(1L)))
				.andExpect(status().isNotFound());
	}

	@Test
	void healthEndpointNeedsNoToken() throws Exception {
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	private static void expectUnauthorized(ResultActions result) throws Exception {
		result.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", "Bearer"))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.title").value("Unauthorized"))
				.andExpect(jsonPath("$.detail").value("Authentication required"));
	}

	private static String signedToken(String secret, Instant expiresAt) {
		return Jwts.builder()
				.subject("1")
				.expiration(Date.from(expiresAt))
				.signWith(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"), Jwts.SIG.HS256)
				.compact();
	}

}
