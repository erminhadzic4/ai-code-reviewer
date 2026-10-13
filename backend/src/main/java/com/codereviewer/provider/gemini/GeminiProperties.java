package com.codereviewer.provider.gemini;

import java.net.URI;
import java.time.Duration;

import com.codereviewer.common.Origins;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Settings for reviewing code with Gemini. Validated when the application starts.
 *
 * <p>The API key is optional here, because these settings are read even when another
 * provider is selected. The Gemini provider itself refuses to start without one.
 *
 * @param apiKey key of a Google project without billing, so that every call stays free
 * @param model the model that reviews the code
 * @param apiUrl origin of the API; the only host the key is ever sent to
 * @param connectTimeout time allowed to open a connection
 * @param readTimeout time allowed for a whole request, from sending it to reading its answer
 * @param maxOutputTokens most tokens the model may answer with
 * @param maxResponseSize size above which an answer is not read any further
 * @param requestsPerMinute most requests the job sends in any 60 seconds
 * @param charactersPerMinute most characters of source the job sends in any 60 seconds
 */
@ConfigurationProperties("app.gemini")
public record GeminiProperties(String apiKey, String model, URI apiUrl, Duration connectTimeout,
		Duration readTimeout, int maxOutputTokens, DataSize maxResponseSize, int requestsPerMinute,
		int charactersPerMinute) {

	public GeminiProperties {
		apiKey = (apiKey == null || apiKey.isBlank()) ? null : apiKey.strip();
		if (model == null || model.isBlank()) {
			throw new IllegalStateException("GEMINI_MODEL is empty. Remove it to use the default, or set it.");
		}
		model = model.strip();
		Origins.requireOrigin("app.gemini.api-url", apiUrl);
		requirePositive("app.gemini.connect-timeout", connectTimeout);
		requirePositive("app.gemini.read-timeout", readTimeout);
		if (maxOutputTokens <= 0) {
			throw new IllegalStateException("app.gemini.max-output-tokens must be positive.");
		}
		// An answer is read into one array, so its limit must fit an int with room to spare.
		if (maxResponseSize == null || maxResponseSize.toBytes() <= 0
				|| maxResponseSize.toBytes() > Integer.MAX_VALUE / 2) {
			throw new IllegalStateException("app.gemini.max-response-size must be positive and below 1 GB.");
		}
		if (requestsPerMinute <= 0) {
			throw new IllegalStateException("app.gemini.requests-per-minute must be positive.");
		}
		if (charactersPerMinute <= 0) {
			throw new IllegalStateException("app.gemini.characters-per-minute must be positive.");
		}
	}

	public boolean hasApiKey() {
		return apiKey != null;
	}

	// The generated toString would print the key.
	@Override
	public String toString() {
		return "GeminiProperties[model=" + model + ", apiUrl=" + apiUrl + ", apiKey=" + (hasApiKey() ? "set" : "none")
				+ "]";
	}

	private static void requirePositive(String name, Duration value) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalStateException(name + " must be a positive duration.");
		}
	}

}
