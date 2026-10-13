package com.codereviewer.review.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import com.codereviewer.config.ReviewProperties;
import com.codereviewer.github.GitHubErrorCode;
import com.codereviewer.github.GitHubException;
import com.codereviewer.provider.ProviderErrorCode;
import com.codereviewer.provider.ProviderException;
import com.codereviewer.provider.ProviderType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class RetryPolicyTest {

	private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");

	private final RetryPolicy policy = new RetryPolicy(3);

	// which failures are tried again

	@ParameterizedTest
	@EnumSource(names = { "RATE_LIMITED", "GITHUB_UNAVAILABLE" })
	void failureOfGitHubThatCanPassIsTriedAgain(GitHubErrorCode code) {
		assertThat(policy.nextAttempt(github(code), 1, NOW)).isPresent();
	}

	@ParameterizedTest
	@EnumSource(names = { "RATE_LIMITED", "GITHUB_UNAVAILABLE" }, mode = EnumSource.Mode.EXCLUDE)
	void failureOfGitHubThatCannotPassEndsTheReview(GitHubErrorCode code) {
		assertThat(policy.nextAttempt(github(code), 1, NOW)).isEmpty();
	}

	@ParameterizedTest
	@EnumSource(names = { "PROVIDER_RATE_LIMITED", "PROVIDER_UNAVAILABLE", "PROVIDER_INVALID_RESPONSE" })
	void failureOfAProviderThatCanPassIsTriedAgain(ProviderErrorCode code) {
		assertThat(policy.nextAttempt(provider(code), 1, NOW)).isPresent();
	}

	@ParameterizedTest
	@EnumSource(names = { "PROVIDER_RATE_LIMITED", "PROVIDER_UNAVAILABLE", "PROVIDER_INVALID_RESPONSE" },
			mode = EnumSource.Mode.EXCLUDE)
	void failureOfAProviderThatCannotPassEndsTheReview(ProviderErrorCode code) {
		assertThat(policy.nextAttempt(provider(code), 1, NOW)).isEmpty();
	}

	@ParameterizedTest
	@EnumSource
	void failureOfTheReviewItselfEndsTheReview(ReviewErrorCode code) {
		assertThat(policy.nextAttempt(JobFailure.of(code, "message"), 1, NOW)).isEmpty();
	}

	// when

	@ParameterizedTest
	@CsvSource({ "1, PT1M", "2, PT5M" })
	void pauseGrowsWithEveryAttempt(int attempt, Duration pause) {
		assertThat(policy.nextAttempt(github(GitHubErrorCode.GITHUB_UNAVAILABLE), attempt, NOW))
				.contains(NOW.plus(pause));
	}

	@ParameterizedTest
	@CsvSource({ "3", "4" })
	void lastAttemptEndsTheReview(int attempt) {
		assertThat(policy.nextAttempt(github(GitHubErrorCode.GITHUB_UNAVAILABLE), attempt, NOW)).isEmpty();
	}

	@Test
	void laterTimeGivenByTheServiceIsUsedInsteadOfThePause() {
		Instant reset = NOW.plus(Duration.ofMinutes(42));

		assertThat(policy.nextAttempt(retryAt(reset), 1, NOW)).contains(reset);
	}

	@Test
	void earlierTimeGivenByTheServiceDoesNotShortenThePause() {
		assertThat(policy.nextAttempt(retryAt(NOW.plusSeconds(5)), 1, NOW)).contains(NOW.plus(Duration.ofMinutes(1)));
		assertThat(policy.nextAttempt(retryAt(NOW.minusSeconds(300)), 2, NOW))
				.contains(NOW.plus(Duration.ofMinutes(5)));
	}

	@Test
	void timeGivenByTheServiceIsBoundedToAnHour() {
		assertThat(policy.nextAttempt(retryAt(NOW.plus(Duration.ofDays(30))), 1, NOW))
				.contains(NOW.plus(Duration.ofHours(1)));
		assertThat(policy.nextAttempt(retryAt(NOW.plus(Duration.ofHours(1))), 1, NOW))
				.contains(NOW.plus(Duration.ofHours(1)));
	}

	@Test
	void timeGivenByTheServiceDoesNotAddAnAttempt() {
		assertThat(policy.nextAttempt(retryAt(NOW.plus(Duration.ofMinutes(10))), 3, NOW)).isEmpty();
	}

	// the configured number of attempts

	@Test
	void singleAttemptMeansNoRetry() {
		RetryPolicy once = new RetryPolicy(1);

		assertThat(once.nextAttempt(github(GitHubErrorCode.GITHUB_UNAVAILABLE), 1, NOW)).isEmpty();
	}

	@Test
	void attemptsBeyondTheThirdWaitAsLongAsTheLastPause() {
		RetryPolicy patient = new RetryPolicy(5);

		assertThat(patient.nextAttempt(github(GitHubErrorCode.GITHUB_UNAVAILABLE), 3, NOW))
				.contains(NOW.plus(Duration.ofMinutes(5)));
		assertThat(patient.nextAttempt(github(GitHubErrorCode.GITHUB_UNAVAILABLE), 4, NOW))
				.contains(NOW.plus(Duration.ofMinutes(5)));
		assertThat(patient.nextAttempt(github(GitHubErrorCode.GITHUB_UNAVAILABLE), 5, NOW)).isEmpty();
	}

	@Test
	void numberOfAttemptsComesFromTheSettings() {
		RetryPolicy configured = new RetryPolicy(new ReviewProperties(ProviderType.FAKE, 200_000, 2));

		assertThat(configured.nextAttempt(github(GitHubErrorCode.GITHUB_UNAVAILABLE), 1, NOW)).isPresent();
		assertThat(configured.nextAttempt(github(GitHubErrorCode.GITHUB_UNAVAILABLE), 2, NOW)).isEmpty();
	}

	private static JobFailure github(GitHubErrorCode code) {
		return JobFailure.of(new GitHubException(code, "message"));
	}

	private static JobFailure provider(ProviderErrorCode code) {
		return JobFailure.of(new ProviderException(code, "message"));
	}

	private static JobFailure retryAt(Instant retryAt) {
		return JobFailure.of(new GitHubException(GitHubErrorCode.RATE_LIMITED, "limited", retryAt));
	}

}
