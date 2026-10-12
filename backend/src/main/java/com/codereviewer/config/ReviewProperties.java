package com.codereviewer.config;

import com.codereviewer.provider.ProviderType;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the review process. Validated when the application starts, so a provider that
 * does not exist stops the startup instead of leaving the application without one.
 *
 * @param provider which implementation reviews the code
 * @param chunkBudget characters of source, line numbers included, sent in one request
 */
@ConfigurationProperties("app.review")
public record ReviewProperties(ProviderType provider, int chunkBudget) {

	public ReviewProperties {
		if (provider == null) {
			throw new IllegalStateException("REVIEW_PROVIDER is empty. Remove it to use the default, or set it.");
		}
		if (chunkBudget <= 0) {
			throw new IllegalStateException("app.review.chunk-budget must be positive.");
		}
	}

}
