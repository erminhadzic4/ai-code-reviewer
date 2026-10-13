package com.codereviewer.config;

import java.time.Duration;

import com.codereviewer.provider.ProviderType;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the review process. Validated when the application starts, so a provider that
 * does not exist stops the startup instead of leaving the application without one.
 *
 * @param provider which implementation reviews the code
 * @param chunkBudget characters of source, line numbers included, sent in one request
 * @param maxAttempts how often a review is tried before it fails for good; every attempt
 * repeats the whole review
 * @param worker the worker that runs queued reviews
 */
@ConfigurationProperties("app.review")
public record ReviewProperties(ProviderType provider, int chunkBudget, int maxAttempts, Worker worker) {

	public ReviewProperties {
		if (provider == null) {
			throw new IllegalStateException("REVIEW_PROVIDER is empty. Remove it to use the default, or set it.");
		}
		if (chunkBudget <= 0) {
			throw new IllegalStateException("app.review.chunk-budget must be positive.");
		}
		if (maxAttempts <= 0) {
			throw new IllegalStateException("app.review.max-attempts must be positive.");
		}
		if (worker == null) {
			throw new IllegalStateException("app.review.worker is missing.");
		}
	}

	/**
	 * @param enabled whether queued reviews are run at all; off in tests, which run them by hand
	 * @param pollInterval how long the worker waits before it looks at the queue again
	 */
	public record Worker(boolean enabled, Duration pollInterval) {

		public Worker {
			if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
				throw new IllegalStateException("app.review.worker.poll-interval must be a positive duration.");
			}
		}

	}

}
