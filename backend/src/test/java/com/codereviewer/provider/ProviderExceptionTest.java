package com.codereviewer.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.Instant;

import com.codereviewer.common.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

class ProviderExceptionTest {

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();

	@ParameterizedTest
	@EnumSource(ProviderErrorCode.class)
	void everyCodeBecomesAProblemDetailWithItsStatus(ProviderErrorCode code) throws Exception {
		mockMvc.perform(get("/test/provider/{code}", code))
				.andExpect(status().is(code.status().value()))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(code.status().value()))
				.andExpect(jsonPath("$.detail").value("Detail for " + code));
	}

	@Test
	void statusesMatchTheMeaningOfEachCode() {
		assertThat(ProviderErrorCode.PROVIDER_RATE_LIMITED.status().value()).isEqualTo(503);
		assertThat(ProviderErrorCode.PROVIDER_QUOTA_EXHAUSTED.status().value()).isEqualTo(503);
		assertThat(ProviderErrorCode.PROVIDER_UNAVAILABLE.status().value()).isEqualTo(502);
		assertThat(ProviderErrorCode.PROVIDER_AUTH_FAILED.status().value()).isEqualTo(502);
		assertThat(ProviderErrorCode.PROVIDER_MISCONFIGURED.status().value()).isEqualTo(502);
		assertThat(ProviderErrorCode.PROVIDER_BLOCKED.status().value()).isEqualTo(422);
		assertThat(ProviderErrorCode.PROVIDER_OUTPUT_TRUNCATED.status().value()).isEqualTo(502);
		assertThat(ProviderErrorCode.PROVIDER_INVALID_RESPONSE.status().value()).isEqualTo(502);
	}

	@Test
	void onlyFailuresThatASecondAttemptCanFixAreRetryable() {
		assertThat(ProviderErrorCode.values()).filteredOn(ProviderErrorCode::retryable)
				.containsExactlyInAnyOrder(ProviderErrorCode.PROVIDER_RATE_LIMITED,
						ProviderErrorCode.PROVIDER_UNAVAILABLE, ProviderErrorCode.PROVIDER_INVALID_RESPONSE);
	}

	@ParameterizedTest
	@EnumSource(ProviderErrorCode.class)
	void everyCodeFitsTheErrorCodeColumnOfAReview(ProviderErrorCode code) {
		assertThat(code.name()).hasSizeLessThanOrEqualTo(40);
	}

	@Test
	void exceptionCarriesCodeRetryTimeAndCause() {
		Instant retryAt = Instant.parse("2026-10-12T12:00:00Z");
		IOException cause = new IOException("connection reset");

		ProviderException rateLimited = new ProviderException(ProviderErrorCode.PROVIDER_RATE_LIMITED, "limit",
				retryAt);
		ProviderException unavailable = new ProviderException(ProviderErrorCode.PROVIDER_UNAVAILABLE, "down", cause);
		ProviderException blocked = new ProviderException(ProviderErrorCode.PROVIDER_BLOCKED, "blocked");

		assertThat(rateLimited.getCode()).isEqualTo(ProviderErrorCode.PROVIDER_RATE_LIMITED);
		assertThat(rateLimited.isRetryable()).isTrue();
		assertThat(rateLimited.getRetryAt()).contains(retryAt);
		assertThat(unavailable.getCause()).isSameAs(cause);
		assertThat(unavailable.getRetryAt()).isEmpty();
		assertThat(blocked.isRetryable()).isFalse();
		assertThat(blocked.getMessage()).isEqualTo("blocked");
	}

	@RestController
	static class TestController {

		@GetMapping("/test/provider/{code}")
		void fail(@PathVariable ProviderErrorCode code) {
			throw new ProviderException(code, "Detail for " + code);
		}

	}

}
