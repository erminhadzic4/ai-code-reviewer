package com.codereviewer.provider.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

class GeminiPropertiesTest {

	private final TestGeminiProperties settings = new TestGeminiProperties();

	// Loads the real application.yml, so the shipped defaults are what is checked. The key and
	// the model are pinned, because a developer's .env may set them.
	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withUserConfiguration(PropertiesConfiguration.class)
			.withPropertyValues("app.gemini.api-key=", "app.gemini.model=gemini-3.5-flash-lite");

	@Test
	void shippedDefaultsAreValid() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			GeminiProperties properties = context.getBean(GeminiProperties.class);
			assertThat(properties.hasApiKey()).isFalse();
			assertThat(properties.apiUrl()).isEqualTo(URI.create("https://generativelanguage.googleapis.com"));
			assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
			assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(120));
			assertThat(properties.maxOutputTokens()).isEqualTo(32_768);
			assertThat(properties.maxResponseSize()).isEqualTo(DataSize.ofMegabytes(2));
		});
	}

	@Test
	void contextFailsToStartWithAnInvalidLimit() {
		contextRunner.withPropertyValues("app.gemini.max-output-tokens=0").run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("app.gemini.max-output-tokens"));
	}

	@Test
	void toStringNeverShowsTheKey() {
		settings.apiKey = "AIza-secret-value-0123456789";

		String text = settings.build().toString();

		assertThat(text).doesNotContain("AIza").doesNotContain("secret").contains("apiKey=set")
				.contains("gemini-3.5-flash-lite");
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", "   " })
	void missingKeyIsAllowedAndReportedAsNone(String apiKey) {
		settings.apiKey = apiKey;

		GeminiProperties properties = settings.build();

		assertThat(properties.hasApiKey()).isFalse();
		assertThat(properties.apiKey()).isNull();
		assertThat(properties.toString()).contains("apiKey=none");
	}

	@Test
	void keyAndModelAreStrippedOfSurroundingSpaces() {
		settings.apiKey = "  key-with-spaces \n";
		settings.model = " gemini-3.8-flash ";

		GeminiProperties properties = settings.build();

		assertThat(properties.apiKey()).isEqualTo("key-with-spaces");
		assertThat(properties.model()).isEqualTo("gemini-3.8-flash");
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", "  " })
	void modelIsRequired(String model) {
		settings.model = model;

		assertThatThrownBy(settings::build).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("GEMINI_MODEL is empty");
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://generativelanguage.googleapis.com", "http://example.com",
			"ftp://generativelanguage.googleapis.com" })
	void apiUrlMustUseHttps(String url) {
		settings.apiUrl = URI.create(url);

		assertThatThrownBy(settings::build).isInstanceOf(IllegalStateException.class)
				.hasMessage("app.gemini.api-url must use https.");
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://127.0.0.1:8080", "http://localhost:8080" })
	void plainHttpIsAcceptedOnLoopbackForTests(String url) {
		settings.apiUrl = URI.create(url);

		assertThatCode(settings::build).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://generativelanguage.googleapis.com/v1beta",
			"https://generativelanguage.googleapis.com/?key=abc", "https://user@generativelanguage.googleapis.com" })
	void apiUrlMustBeAnOriginOnly(String url) {
		settings.apiUrl = URI.create(url);

		assertThatThrownBy(settings::build).isInstanceOf(IllegalStateException.class)
				.hasMessage("app.gemini.api-url must contain only scheme, host and port.");
	}

	@Test
	void apiUrlIsRequired() {
		settings.apiUrl = null;

		assertThatThrownBy(settings::build).isInstanceOf(IllegalStateException.class)
				.hasMessage("app.gemini.api-url must be an absolute URL.");
	}

	@Test
	void timeoutsMustBePositive() {
		settings.connectTimeout = Duration.ZERO;
		assertThatThrownBy(settings::build).hasMessage("app.gemini.connect-timeout must be a positive duration.");

		settings.connectTimeout = Duration.ofSeconds(5);
		settings.readTimeout = Duration.ofSeconds(-1);
		assertThatThrownBy(settings::build).hasMessage("app.gemini.read-timeout must be a positive duration.");

		settings.readTimeout = null;
		assertThatThrownBy(settings::build).hasMessage("app.gemini.read-timeout must be a positive duration.");
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, -1 })
	void outputLimitMustBePositive(int tokens) {
		settings.maxOutputTokens = tokens;

		assertThatThrownBy(settings::build).isInstanceOf(IllegalStateException.class)
				.hasMessage("app.gemini.max-output-tokens must be positive.");
	}

	@Test
	void responseSizeMustBePositiveAndFitInMemory() {
		settings.maxResponseSize = DataSize.ofBytes(0);
		assertThatThrownBy(settings::build)
				.hasMessage("app.gemini.max-response-size must be positive and below 1 GB.");

		settings.maxResponseSize = DataSize.ofGigabytes(2);
		assertThatThrownBy(settings::build)
				.hasMessage("app.gemini.max-response-size must be positive and below 1 GB.");

		settings.maxResponseSize = null;
		assertThatThrownBy(settings::build)
				.hasMessage("app.gemini.max-response-size must be positive and below 1 GB.");
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(GeminiProperties.class)
	static class PropertiesConfiguration {
	}

}
