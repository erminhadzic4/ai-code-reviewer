package com.codereviewer.review.job;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.codereviewer.config.ReviewProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Decides what happens to a review after a failed attempt: another attempt later, or the
 * end. It only computes; it reads no clock and changes nothing.
 *
 * <p>Another attempt repeats the whole review, chunks that were already reviewed included,
 * so the number of attempts is small and the pause grows.
 */
@Component
public class RetryPolicy {

	/** Pause after the first and after the second attempt; later ones wait as long as the last. */
	static final List<Duration> DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5));

	/** The time a service gives comes from a response header, so it is not trusted without a bound. */
	static final Duration MAX_DELAY = Duration.ofHours(1);

	private final int maxAttempts;

	@Autowired
	public RetryPolicy(ReviewProperties properties) {
		this(properties.maxAttempts());
	}

	RetryPolicy(int maxAttempts) {
		this.maxAttempts = maxAttempts;
	}

	/**
	 * @param attempt the number of the attempt that just failed, counted from 1
	 * @return the earliest time of the next attempt, or empty when the review has failed for good
	 */
	public Optional<Instant> nextAttempt(JobFailure failure, int attempt, Instant now) {
		if (!failure.retryable() || attempt >= maxAttempts) {
			return Optional.empty();
		}
		Instant next = now.plus(DELAYS.get(Math.min(attempt, DELAYS.size()) - 1));
		// Earlier than the service asked for would only fail again.
		if (failure.retryAt() != null && failure.retryAt().isAfter(next)) {
			next = failure.retryAt();
		}
		Instant latest = now.plus(MAX_DELAY);
		return Optional.of(next.isAfter(latest) ? latest : next);
	}

}
