package com.codereviewer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class JwtPropertiesTest {

	private static final Duration EXPIRATION = Duration.ofHours(24);
	private static final String SECRET_OF_32_BYTES = "0123456789abcdef0123456789abcdef";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(PropertiesConfiguration.class)
			.withPropertyValues("app.jwt.expiration=24h");

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", "   " })
	void missingSecretIsRejected(String secret) {
		assertThatThrownBy(() -> new JwtProperties(secret, EXPIRATION))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("JWT_SECRET is not set");
	}

	@Test
	void secretShorterThan32BytesIsRejected() {
		String secretOf31Bytes = SECRET_OF_32_BYTES.substring(1);

		assertThatThrownBy(() -> new JwtProperties(secretOf31Bytes, EXPIRATION))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("at least 32 bytes");
	}

	@Test
	void secretOf32BytesIsAccepted() {
		assertThatCode(() -> new JwtProperties(SECRET_OF_32_BYTES, EXPIRATION)).doesNotThrowAnyException();
	}

	@Test
	void exampleSecretIsRejectedEvenThoughItIsLongEnough() {
		assertThat(JwtProperties.EXAMPLE_SECRET.length()).isGreaterThanOrEqualTo(JwtProperties.MIN_SECRET_BYTES);

		assertThatThrownBy(() -> new JwtProperties(JwtProperties.EXAMPLE_SECRET, EXPIRATION))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("example value");
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "PT0S", "PT-1H" })
	void expirationMustBePositive(Duration expiration) {
		assertThatThrownBy(() -> new JwtProperties(SECRET_OF_32_BYTES, expiration))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("expiration");
	}

	@Test
	void contextStartsWithAStrongSecret() {
		contextRunner.withPropertyValues("app.jwt.secret=" + SECRET_OF_32_BYTES).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(JwtProperties.class).expiration()).isEqualTo(EXPIRATION);
		});
	}

	@Test
	void contextFailsToStartWithoutASecret() {
		contextRunner.withPropertyValues("app.jwt.secret=").run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("JWT_SECRET is not set"));
	}

	@Test
	void contextFailsToStartWithAWeakSecret() {
		contextRunner.withPropertyValues("app.jwt.secret=too-short").run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("at least 32 bytes"));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(JwtProperties.class)
	static class PropertiesConfiguration {
	}

}
