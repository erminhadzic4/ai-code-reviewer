package com.codereviewer.provider.gemini;

import java.net.URI;
import java.time.Duration;

import org.springframework.util.unit.DataSize;

/** Settings for tests: the shipped defaults, with any of them replaced. */
final class TestGeminiProperties {

	String apiKey = "test-key-0123456789";
	String model = "gemini-3.5-flash-lite";
	URI apiUrl = URI.create("https://generativelanguage.googleapis.com");
	Duration connectTimeout = Duration.ofSeconds(5);
	Duration readTimeout = Duration.ofSeconds(120);
	int maxOutputTokens = 32_768;
	DataSize maxResponseSize = DataSize.ofMegabytes(2);

	GeminiProperties build() {
		return new GeminiProperties(apiKey, model, apiUrl, connectTimeout, readTimeout, maxOutputTokens,
				maxResponseSize);
	}

}
