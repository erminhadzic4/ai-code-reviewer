package com.codereviewer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codereviewer.provider.ProviderType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ReviewPropertiesTest {

	// Loads the real application.yml, so the shipped defaults are what is checked. The provider
	// is pinned, because a developer's .env may set it.
	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withUserConfiguration(PropertiesConfiguration.class)
			.withPropertyValues("app.review.provider=fake");

	@Test
	void shippedDefaultsAreValid() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			ReviewProperties properties = context.getBean(ReviewProperties.class);
			assertThat(properties.chunkBudget()).isEqualTo(200_000);
			assertThat(properties.maxAttempts()).isEqualTo(3);
		});
	}

	@Test
	void contextFailsToStartWithAnInvalidNumberOfAttempts() {
		contextRunner.withPropertyValues("app.review.max-attempts=0").run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("app.review.max-attempts"));
	}

	@Test
	void validSettingsAreAccepted() {
		ReviewProperties properties = new ReviewProperties(ProviderType.FAKE, 200_000, 3);

		assertThat(properties.provider()).isEqualTo(ProviderType.FAKE);
		assertThat(properties.chunkBudget()).isEqualTo(200_000);
		assertThat(properties.maxAttempts()).isEqualTo(3);
	}

	@Test
	void missingProviderIsRejected() {
		assertThatThrownBy(() -> new ReviewProperties(null, 200_000, 3)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("REVIEW_PROVIDER is empty");
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, -1 })
	void chunkBudgetMustBePositive(int budget) {
		assertThatThrownBy(() -> new ReviewProperties(ProviderType.FAKE, budget, 3))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("app.review.chunk-budget must be positive.");
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, -1 })
	void numberOfAttemptsMustBePositive(int attempts) {
		assertThatThrownBy(() -> new ReviewProperties(ProviderType.FAKE, 200_000, attempts))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("app.review.max-attempts must be positive.");
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(ReviewProperties.class)
	static class PropertiesConfiguration {
	}

}
