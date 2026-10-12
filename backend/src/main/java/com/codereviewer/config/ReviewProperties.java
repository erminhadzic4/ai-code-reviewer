package com.codereviewer.config;

import com.codereviewer.provider.ProviderType;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the review process. Validated when the application starts, so a provider that
 * does not exist stops the startup instead of leaving the application without one.
 *
 * @param provider which implementation reviews the code
 */
@ConfigurationProperties("app.review")
public record ReviewProperties(ProviderType provider) {

	public ReviewProperties {
		if (provider == null) {
			throw new IllegalStateException("REVIEW_PROVIDER is empty. Remove it to use the default, or set it.");
		}
	}

}
