package com.codereviewer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.codereviewer.IntegrationTest;
import com.codereviewer.user.User;
import com.codereviewer.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class AuthFlowIntegrationTest {

	private static final String PASSWORD = "correct-horse";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Test
	void registerCreatesTheUserAndReturnsAToken() throws Exception {
		String email = uniqueEmail();

		String body = register(email, PASSWORD)
				.andExpect(status().isCreated())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.expiresIn").value(86_400))
				.andExpect(jsonPath("$.user.id").isNumber())
				.andExpect(jsonPath("$.user.email").value(email))
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$.user.length()").value(2))
				.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain(PASSWORD).doesNotContainIgnoringCase("password").doesNotContain("$2");
	}

	@Test
	void registerStoresALowerCaseEmailAndABcryptHash() throws Exception {
		String email = uniqueEmail();

		register("  " + email.toUpperCase() + " ", PASSWORD).andExpect(status().isCreated())
				.andExpect(jsonPath("$.user.email").value(email));

		User stored = userRepository.findByEmail(email).orElseThrow();
		assertThat(stored.getPasswordHash()).startsWith("$2").isNotEqualTo(PASSWORD);
		assertThat(stored.getCreatedAt()).isNotNull();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidRegistrations")
	void registerRejectsInvalidInput(String description, String json, String field, String message) throws Exception {
		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.detail").value("Validation failed"))
				.andExpect(jsonPath("$.errors.length()").value(1))
				.andExpect(jsonPath("$.errors[0].field").value(field))
				.andExpect(jsonPath("$.errors[0].message").value(message));
	}

	static Stream<Arguments> invalidRegistrations() {
		return Stream.of(
				Arguments.of("email without a domain", credentials("ana", PASSWORD), "email",
						"must be a valid email address"),
				Arguments.of("email without a dot in the domain", credentials("ana@localhost", PASSWORD), "email",
						"must be a valid email address"),
				Arguments.of("password of 7 characters", credentials("ana@example.com", "1234567"), "password",
						"must be at least 8 characters"),
				Arguments.of("password of 73 bytes", credentials("ana@example.com", "a".repeat(73)), "password",
						"must be at most 72 bytes"),
				Arguments.of("password of 37 two-byte characters", credentials("ana@example.com", "š".repeat(37)),
						"password", "must be at most 72 bytes"),
				Arguments.of("missing email", "{\"password\":\"" + PASSWORD + "\"}", "email", "is required"),
				Arguments.of("missing password", "{\"email\":\"ana@example.com\"}", "password", "is required"));
	}

	@Test
	void registerRejectsMalformedJson() throws Exception {
		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{not json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Malformed request body"));
	}

	@Test
	void registerRejectsAnEmailThatDiffersOnlyInCase() throws Exception {
		String email = uniqueEmail();
		register(email, PASSWORD).andExpect(status().isCreated());

		register(email.toUpperCase(), "another-password")
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.detail").value("Email is already registered"));
	}

	@Test
	void concurrentRegistrationsOfOneEmailCreateOneUserAndRejectTheRest() throws Exception {
		String email = uniqueEmail();
		int requests = 8;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(requests);
		try {
			List<Future<Integer>> responses = new ArrayList<>();
			for (int i = 0; i < requests; i++) {
				responses.add(executor.submit(() -> {
					start.await();
					return register(email, PASSWORD).andReturn().getResponse().getStatus();
				}));
			}
			start.countDown();

			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> response : responses) {
				statuses.add(response.get(30, TimeUnit.SECONDS));
			}

			// Hashing the password takes far longer than the "is it taken" check, so several
			// requests pass that check together and only the unique constraint stops them.
			assertThat(statuses).containsOnly(201, 409);
			assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
		}
		finally {
			executor.shutdownNow();
		}
		assertThat(userRepository.findAll()).filteredOn(user -> user.getEmail().equals(email)).hasSize(1);
	}

	@Test
	void tokenFromLoginGivesAccessToTheCurrentUser() throws Exception {
		String email = uniqueEmail();
		register(email, PASSWORD).andExpect(status().isCreated());

		String body = login(email, PASSWORD)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.expiresIn").value(86_400))
				.andExpect(jsonPath("$.user.email").value(email))
				.andReturn().getResponse().getContentAsString();
		String token = JsonPath.read(body, "$.accessToken");
		Integer id = JsonPath.read(body, "$.user.id");

		mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.length()").value(2));
	}

	@Test
	void tokenFromRegistrationGivesAccessToTheCurrentUser() throws Exception {
		String email = uniqueEmail();
		String body = register(email, PASSWORD).andReturn().getResponse().getContentAsString();
		String token = JsonPath.read(body, "$.accessToken");

		mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value(email));
	}

	@Test
	void loginIgnoresTheCaseOfTheEmail() throws Exception {
		String email = uniqueEmail();
		register(email, PASSWORD).andExpect(status().isCreated());

		login(" " + email.toUpperCase(), PASSWORD).andExpect(status().isOk());
	}

	@Test
	void loginDoesNotRevealWhetherAnEmailIsRegistered() throws Exception {
		String email = uniqueEmail();
		register(email, PASSWORD).andExpect(status().isCreated());

		String wrongPassword = login(email, "wrong-password")
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.detail").value("Invalid email or password"))
				.andReturn().getResponse().getContentAsString();
		String unknownEmail = login(uniqueEmail(), PASSWORD)
				.andExpect(status().isUnauthorized())
				.andReturn().getResponse().getContentAsString();

		assertThat(unknownEmail).isEqualTo(wrongPassword);
	}

	@Test
	void loginWithAPasswordTooLongForBcryptIsUnauthorized() throws Exception {
		String email = uniqueEmail();
		register(email, PASSWORD).andExpect(status().isCreated());

		login(email, "a".repeat(200)).andExpect(status().isUnauthorized());
	}

	@Test
	void loginWithBlankFieldsIsABadRequest() throws Exception {
		login("", "")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.length()").value(2))
				.andExpect(jsonPath("$.errors[0].field").value("email"))
				.andExpect(jsonPath("$.errors[1].field").value("password"));
	}

	private ResultActions register(String email, String password) throws Exception {
		return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(credentials(email, password)));
	}

	private ResultActions login(String email, String password) throws Exception {
		return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(credentials(email, password)));
	}

	private static String credentials(String email, String password) {
		return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
	}

	// The database is shared by all integration tests, so every test uses its own address.
	private static String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

}
