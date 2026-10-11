package com.codereviewer.github;

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

class GitHubPropertiesTest {

	private static final URI API = URI.create("https://api.github.com");
	private static final URI ARCHIVE = URI.create("https://codeload.github.com");
	private static final Duration TIMEOUT = Duration.ofSeconds(5);
	private static final DataSize SIZE = DataSize.ofMegabytes(1);

	// Loads the real application.yml, so the shipped defaults are what is checked.
	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withUserConfiguration(PropertiesConfiguration.class);

	@Test
	void shippedDefaultsAreValid() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			GitHubProperties properties = context.getBean(GitHubProperties.class);
			assertThat(properties.apiUrl()).isEqualTo(API);
			assertThat(properties.archiveUrl()).isEqualTo(ARCHIVE);
			assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
			assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(30));
			assertThat(properties.downloadTimeout()).isEqualTo(Duration.ofSeconds(60));
			assertThat(properties.maxRepoSize()).isEqualTo(DataSize.ofGigabytes(1));
			assertThat(properties.maxDownloadSize()).isEqualTo(DataSize.ofMegabytes(50));
			assertThat(properties.maxUncompressedSize()).isEqualTo(DataSize.ofMegabytes(200));
			assertThat(properties.maxEntries()).isEqualTo(20_000);
			assertThat(properties.maxFileSize()).isEqualTo(DataSize.ofKilobytes(200));
			assertThat(properties.maxFiles()).isEqualTo(500);
			assertThat(properties.maxSourceSize()).isEqualTo(DataSize.ofMegabytes(2));
		});
	}

	@Test
	void contextFailsToStartWithAnInvalidLimit() {
		contextRunner.withPropertyValues("app.github.max-files=0").run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("app.github.max-files"));
	}

	@Test
	void tokenFromConfigurationIsUsed() {
		contextRunner.withPropertyValues("app.github.token=ghp_example").run(context -> {
			GitHubProperties properties = context.getBean(GitHubProperties.class);
			assertThat(properties.hasToken()).isTrue();
			assertThat(properties.token()).isEqualTo("ghp_example");
		});
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", "   " })
	void blankTokenMeansNoToken(String token) {
		GitHubProperties properties = withToken(token);

		assertThat(properties.hasToken()).isFalse();
		assertThat(properties.token()).isNull();
	}

	@Test
	void tokenIsTrimmed() {
		assertThat(withToken("  ghp_example\n").token()).isEqualTo("ghp_example");
	}

	@Test
	void tokenIsNotPrinted() {
		assertThat(withToken("ghp_secret-value").toString()).doesNotContain("ghp_secret-value").contains("token=set");
		assertThat(withToken(null).toString()).contains("token=none");
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://api.github.com", "https://api.github.com:8443", "http://127.0.0.1:8080",
			"http://localhost:8080" })
	void originsAreAccepted(String url) {
		assertThatCode(() -> withApiUrl(URI.create(url))).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {
			"api.github.com",
			"/repos",
			// Plain HTTP would send the token unencrypted.
			"http://api.github.com",
			// The loopback exception compares the whole host, not a prefix.
			"http://127.0.0.1.evil.com",
			"http://localhost.evil.com",
			"http://evil.com/localhost",
			"http://localhost@evil.com",
			"http://127.0.0.2",
			"ftp://api.github.com",
			"https://api.github.com/",
			"https://api.github.com/v3",
			"https://api.github.com?x=1",
			"https://api.github.com#x",
			"https://user@api.github.com" })
	void anythingButAnOriginIsRejected(URI url) {
		assertThatThrownBy(() -> withApiUrl(url)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("app.github.api-url");
	}

	@Test
	void archiveUrlIsValidatedToo() {
		assertThatThrownBy(() -> new GitHubProperties(null, API, URI.create("http://codeload.github.com"), TIMEOUT,
				TIMEOUT, TIMEOUT, SIZE, SIZE, SIZE, 1, SIZE, 1, SIZE))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("app.github.archive-url");
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "PT0S", "PT-1S" })
	void timeoutsMustBePositive(Duration timeout) {
		assertThatThrownBy(() -> new GitHubProperties(null, API, ARCHIVE, timeout, TIMEOUT, TIMEOUT, SIZE, SIZE, SIZE,
				1, SIZE, 1, SIZE)).hasMessageContaining("app.github.connect-timeout");
		assertThatThrownBy(() -> new GitHubProperties(null, API, ARCHIVE, TIMEOUT, timeout, TIMEOUT, SIZE, SIZE, SIZE,
				1, SIZE, 1, SIZE)).hasMessageContaining("app.github.read-timeout");
		assertThatThrownBy(() -> new GitHubProperties(null, API, ARCHIVE, TIMEOUT, TIMEOUT, timeout, SIZE, SIZE, SIZE,
				1, SIZE, 1, SIZE)).hasMessageContaining("app.github.download-timeout");
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "0B", "-1KB" })
	void sizesMustBePositive(DataSize size) {
		assertThatThrownBy(() -> new GitHubProperties(null, API, ARCHIVE, TIMEOUT, TIMEOUT, TIMEOUT, size, SIZE, SIZE,
				1, SIZE, 1, SIZE)).hasMessageContaining("app.github.max-repo-size");
		assertThatThrownBy(() -> new GitHubProperties(null, API, ARCHIVE, TIMEOUT, TIMEOUT, TIMEOUT, SIZE, size, SIZE,
				1, SIZE, 1, SIZE)).hasMessageContaining("app.github.max-download-size");
		assertThatThrownBy(() -> new GitHubProperties(null, API, ARCHIVE, TIMEOUT, TIMEOUT, TIMEOUT, SIZE, SIZE, size,
				1, SIZE, 1, SIZE)).hasMessageContaining("app.github.max-uncompressed-size");
		assertThatThrownBy(() -> new GitHubProperties(null, API, ARCHIVE, TIMEOUT, TIMEOUT, TIMEOUT, SIZE, SIZE, SIZE,
				1, size, 1, SIZE)).hasMessageContaining("app.github.max-file-size");
		assertThatThrownBy(() -> new GitHubProperties(null, API, ARCHIVE, TIMEOUT, TIMEOUT, TIMEOUT, SIZE, SIZE, SIZE,
				1, SIZE, 1, size)).hasMessageContaining("app.github.max-source-size");
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, -1 })
	void countsMustBePositive(int count) {
		assertThatThrownBy(() -> new GitHubProperties(null, API, ARCHIVE, TIMEOUT, TIMEOUT, TIMEOUT, SIZE, SIZE, SIZE,
				count, SIZE, 1, SIZE)).hasMessageContaining("app.github.max-entries");
		assertThatThrownBy(() -> new GitHubProperties(null, API, ARCHIVE, TIMEOUT, TIMEOUT, TIMEOUT, SIZE, SIZE, SIZE,
				1, SIZE, count, SIZE)).hasMessageContaining("app.github.max-files");
	}

	@Test
	void fileSizeLimitMustFitInMemory() {
		assertThatThrownBy(() -> new GitHubProperties(null, API, ARCHIVE, TIMEOUT, TIMEOUT, TIMEOUT, SIZE, SIZE, SIZE,
				1, DataSize.ofGigabytes(2), 1, SIZE)).hasMessageContaining("app.github.max-file-size");
	}

	private static GitHubProperties withToken(String token) {
		return new GitHubProperties(token, API, ARCHIVE, TIMEOUT, TIMEOUT, TIMEOUT, SIZE, SIZE, SIZE, 1, SIZE, 1,
				SIZE);
	}

	private static GitHubProperties withApiUrl(URI apiUrl) {
		return new GitHubProperties(null, apiUrl, ARCHIVE, TIMEOUT, TIMEOUT, TIMEOUT, SIZE, SIZE, SIZE, 1, SIZE, 1,
				SIZE);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(GitHubProperties.class)
	static class PropertiesConfiguration {
	}

}
