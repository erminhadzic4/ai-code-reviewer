package com.codereviewer.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.codereviewer.config.ReviewProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class ReviewProviderSelectionTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(ProviderConfiguration.class);

	@ParameterizedTest
	@ValueSource(strings = { "fake", "FAKE" })
	void fakeProviderIsUsedWhenSelected(String value) {
		contextRunner.withPropertyValues("app.review.provider=" + value).run(context -> {
			assertThat(context).hasSingleBean(CodeReviewProvider.class);
			assertThat(context.getBean(CodeReviewProvider.class)).isInstanceOf(FakeReviewProvider.class);
		});
	}

	@Test
	void fakeProviderIsTheDefaultOfTheApplication() {
		// Reads application.yml, where the default lives.
		contextRunner.withInitializer(new ConfigDataApplicationContextInitializer()).run(context -> {
			assertThat(context).hasSingleBean(CodeReviewProvider.class);
			assertThat(context.getBean(CodeReviewProvider.class)).isInstanceOf(FakeReviewProvider.class);
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "gemini", "ollama", "fakes" })
	void unknownProviderStopsTheStartup(String value) {
		contextRunner.withPropertyValues("app.review.provider=" + value).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining("app.review.provider");
		});
	}

	@Test
	void emptyProviderStopsTheStartup() {
		contextRunner.withPropertyValues("app.review.provider=").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining("REVIEW_PROVIDER is empty");
		});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(ReviewProperties.class)
	@Import(FakeReviewProvider.class)
	static class ProviderConfiguration {
	}

}
