package com.codereviewer.github;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class GitHubExceptionTest {

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();

	@ParameterizedTest
	@EnumSource(GitHubErrorCode.class)
	void everyCodeBecomesAProblemDetailWithItsStatus(GitHubErrorCode code) throws Exception {
		mockMvc.perform(get("/test/github/{code}", code))
				.andExpect(status().is(code.status().value()))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(code.status().value()))
				.andExpect(jsonPath("$.detail").value("Detail for " + code));
	}

	@Test
	void invalidUrlBecomesABadRequest() throws Exception {
		mockMvc.perform(get("/test/parse").param("url", "https://gitlab.com/owner/repo"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.detail")
						.value("Enter a GitHub repository URL in the form https://github.com/owner/repository"));
	}

	@Test
	void statusesMatchTheMeaningOfEachCode() {
		assertThat(GitHubErrorCode.REPO_NOT_FOUND.status().value()).isEqualTo(404);
		assertThat(GitHubErrorCode.REPO_UNAVAILABLE.status().value()).isEqualTo(404);
		assertThat(GitHubErrorCode.REPO_EMPTY.status().value()).isEqualTo(422);
		assertThat(GitHubErrorCode.REPO_TOO_LARGE.status().value()).isEqualTo(422);
		assertThat(GitHubErrorCode.RATE_LIMITED.status().value()).isEqualTo(503);
		assertThat(GitHubErrorCode.GITHUB_AUTH_FAILED.status().value()).isEqualTo(502);
		assertThat(GitHubErrorCode.GITHUB_UNAVAILABLE.status().value()).isEqualTo(502);
		assertThat(GitHubErrorCode.ARCHIVE_INVALID.status().value()).isEqualTo(502);
	}

	@Test
	void onlyTransientFailuresAreRetryable() {
		assertThat(GitHubErrorCode.values()).filteredOn(GitHubErrorCode::retryable)
				.containsExactlyInAnyOrder(GitHubErrorCode.RATE_LIMITED, GitHubErrorCode.GITHUB_UNAVAILABLE);
	}

	@Test
	void exceptionCarriesCodeRetryTimeAndCause() {
		Instant resetsAt = Instant.parse("2026-10-11T12:00:00Z");
		IOException cause = new IOException("connection reset");

		GitHubException rateLimited = new GitHubException(GitHubErrorCode.RATE_LIMITED, "limit", resetsAt);
		GitHubException unavailable = new GitHubException(GitHubErrorCode.GITHUB_UNAVAILABLE, "down", cause);
		GitHubException notFound = new GitHubException(GitHubErrorCode.REPO_NOT_FOUND, "missing");

		assertThat(rateLimited.getCode()).isEqualTo(GitHubErrorCode.RATE_LIMITED);
		assertThat(rateLimited.isRetryable()).isTrue();
		assertThat(rateLimited.getRetryAt()).contains(resetsAt);
		assertThat(unavailable.getCause()).isSameAs(cause);
		assertThat(unavailable.getRetryAt()).isEmpty();
		assertThat(notFound.isRetryable()).isFalse();
		assertThat(notFound.getMessage()).isEqualTo("missing");
	}

	@RestController
	static class TestController {

		@GetMapping("/test/github/{code}")
		void fail(@PathVariable GitHubErrorCode code) {
			throw new GitHubException(code, "Detail for " + code);
		}

		@GetMapping("/test/parse")
		RepoRef parse(@RequestParam String url) {
			return RepoUrlParser.parse(url);
		}

	}

}
