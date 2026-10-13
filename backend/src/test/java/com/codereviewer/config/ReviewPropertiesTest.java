package com.codereviewer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import com.codereviewer.provider.ProviderType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ReviewPropertiesTest {

	private static final ReviewProperties.Worker WORKER = new ReviewProperties.Worker(true, Duration.ofSeconds(1));

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
			assertThat(properties.worker().enabled()).isTrue();
			assertThat(properties.worker().pollInterval()).isEqualTo(Duration.ofSeconds(1));
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
	void contextFailsToStartWithAnInvalidPollInterval() {
		contextRunner.withPropertyValues("app.review.worker.poll-interval=0s").run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("app.review.worker.poll-interval"));
	}

	@Test
	void workerCanBeSwitchedOff() {
		contextRunner.withPropertyValues("app.review.worker.enabled=false")
				.run(context -> assertThat(context.getBean(ReviewProperties.class).worker().enabled()).isFalse());
	}

	@Test
	void validSettingsAreAccepted() {
		ReviewProperties properties = new ReviewProperties(ProviderType.FAKE, 200_000, 3, WORKER);

		assertThat(properties.provider()).isEqualTo(ProviderType.FAKE);
		assertThat(properties.chunkBudget()).isEqualTo(200_000);
		assertThat(properties.maxAttempts()).isEqualTo(3);
		assertThat(properties.worker()).isEqualTo(WORKER);
	}

	@Test
	void missingProviderIsRejected() {
		assertThatThrownBy(() -> new ReviewProperties(null, 200_000, 3, WORKER))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("REVIEW_PROVIDER is empty");
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, -1 })
	void chunkBudgetMustBePositive(int budget) {
		assertThatThrownBy(() -> new ReviewProperties(ProviderType.FAKE, budget, 3, WORKER))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("app.review.chunk-budget must be positive.");
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, -1 })
	void numberOfAttemptsMustBePositive(int attempts) {
		assertThatThrownBy(() -> new ReviewProperties(ProviderType.FAKE, 200_000, attempts, WORKER))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("app.review.max-attempts must be positive.");
	}

	@Test
	void workerSettingsAreRequired() {
		assertThatThrownBy(() -> new ReviewProperties(ProviderType.FAKE, 200_000, 3, null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("app.review.worker is missing.");
	}

	@Test
	void pollIntervalMustBePositive() {
		String message = "app.review.worker.poll-interval must be a positive duration.";

		assertThatThrownBy(() -> new ReviewProperties.Worker(true, Duration.ZERO)).hasMessage(message);
		assertThatThrownBy(() -> new ReviewProperties.Worker(true, Duration.ofSeconds(-1))).hasMessage(message);
		assertThatThrownBy(() -> new ReviewProperties.Worker(false, null)).hasMessage(message);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(ReviewProperties.class)
	static class PropertiesConfiguration {
	}

}
