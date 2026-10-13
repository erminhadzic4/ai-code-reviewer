package com.codereviewer.review.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Stream;

import com.codereviewer.github.GitHubErrorCode;
import com.codereviewer.github.GitHubException;
import com.codereviewer.provider.ProviderErrorCode;
import com.codereviewer.provider.ProviderException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class JobFailureTest {

	private static final Instant RETRY_AT = Instant.parse("2026-10-13T10:30:00Z");

	@ParameterizedTest
	@EnumSource
	void failureOfGitHubKeepsItsCodeItsMessageAndWhetherItCanPass(GitHubErrorCode code) {
		JobFailure failure = JobFailure.of(new GitHubException(code, "Message for the user"));

		assertThat(failure.code()).isEqualTo(code.name());
		assertThat(failure.message()).isEqualTo("Message for the user");
		assertThat(failure.retryable()).isEqualTo(code.retryable());
		assertThat(failure.retryAt()).isNull();
	}

	@ParameterizedTest
	@EnumSource
	void failureOfAProviderKeepsItsCodeItsMessageAndWhetherItCanPass(ProviderErrorCode code) {
		JobFailure failure = JobFailure.of(new ProviderException(code, "Message for the user"));

		assertThat(failure.code()).isEqualTo(code.name());
		assertThat(failure.message()).isEqualTo("Message for the user");
		assertThat(failure.retryable()).isEqualTo(code.retryable());
		assertThat(failure.retryAt()).isNull();
	}

	@Test
	void timeTheServiceGaveIsKept() {
		assertThat(JobFailure.of(new GitHubException(GitHubErrorCode.RATE_LIMITED, "limited", RETRY_AT)).retryAt())
				.isEqualTo(RETRY_AT);
		assertThat(JobFailure.of(new ProviderException(ProviderErrorCode.PROVIDER_RATE_LIMITED, "limited", RETRY_AT))
				.retryAt()).isEqualTo(RETRY_AT);
	}

	@ParameterizedTest
	@EnumSource
	void failureOfTheReviewItselfIsNeverTriedAgain(ReviewErrorCode code) {
		JobFailure failure = JobFailure.of(code, "Message for the user");

		assertThat(failure.code()).isEqualTo(code.name());
		assertThat(failure.message()).isEqualTo("Message for the user");
		assertThat(failure.retryable()).isFalse();
		assertThat(failure.retryAt()).isNull();
	}

	// reviews.error_code is VARCHAR(40) and has no list of allowed values.
	@ParameterizedTest
	@MethodSource("everyErrorCode")
	void everyErrorCodeFitsItsColumn(Enum<?> code) {
		assertThat(code.name()).hasSizeLessThanOrEqualTo(40);
	}

	@Test
	void noTwoSourcesShareACode() {
		assertThat(everyErrorCode().map(Enum::name)).doesNotHaveDuplicates();
	}

	static Stream<Enum<?>> everyErrorCode() {
		return Stream.<Enum<?>[]>of(GitHubErrorCode.values(), ProviderErrorCode.values(), ReviewErrorCode.values())
				.flatMap(Arrays::stream);
	}

}
