package com.codereviewer.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import com.codereviewer.config.ReviewProperties;
import com.codereviewer.provider.gemini.GeminiProperties;
import com.codereviewer.provider.gemini.GeminiReviewProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class ReviewProviderSelectionTest {

	// Loads the real application.yml for the settings of both providers. The provider and
	// the key are set by each test, because a developer's .env may set them too.
	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withUserConfiguration(ProviderConfiguration.class)
			.withPropertyValues("app.gemini.api-key=");

	@ParameterizedTest
	@ValueSource(strings = { "fake", "FAKE" })
	void fakeProviderIsUsedWhenSelectedAndNeedsNoKey(String value) {
		contextRunner.withPropertyValues("app.review.provider=" + value).run(context -> {
			assertThat(context).hasSingleBean(CodeReviewProvider.class);
			assertThat(context.getBean(CodeReviewProvider.class)).isInstanceOf(FakeReviewProvider.class);
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "gemini", "GEMINI" })
	void geminiProviderIsUsedWhenSelectedAndIsTheOnlyOne(String value) {
		contextRunner.withPropertyValues("app.review.provider=" + value, "app.gemini.api-key=test-key").run(context -> {
			assertThat(context).hasSingleBean(CodeReviewProvider.class);
			assertThat(context.getBean(CodeReviewProvider.class)).isInstanceOf(GeminiReviewProvider.class);
		});
	}

	@Test
	void geminiProviderWithoutAKeyStopsTheStartup() {
		contextRunner.withPropertyValues("app.review.provider=gemini").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining("GEMINI_API_KEY is not set");
		});
	}

	@Test
	void fakeProviderIsTheDefaultOfTheApplication() throws IOException {
		// Read from the file itself: a developer's .env may select another provider.
		List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application",
				new ClassPathResource("application.yml"));

		assertThat(sources.getFirst().getProperty("app.review.provider")).hasToString("${REVIEW_PROVIDER:fake}");
	}

	@ParameterizedTest
	@ValueSource(strings = { "ollama", "openai", "fakes" })
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
	@EnableConfigurationProperties({ ReviewProperties.class, GeminiProperties.class })
	@Import({ FakeReviewProvider.class, GeminiReviewProvider.class })
	static class ProviderConfiguration {
	}

}
