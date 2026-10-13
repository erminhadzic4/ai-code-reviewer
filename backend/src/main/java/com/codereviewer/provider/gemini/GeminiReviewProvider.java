package com.codereviewer.provider.gemini;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.codereviewer.provider.CodeReviewProvider;
import com.codereviewer.provider.FindingCategory;
import com.codereviewer.provider.FindingKind;
import com.codereviewer.provider.ProviderErrorCode;
import com.codereviewer.provider.ProviderException;
import com.codereviewer.provider.ProviderLimits;
import com.codereviewer.provider.RawFinding;
import com.codereviewer.provider.ReviewChunk;
import com.codereviewer.provider.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reviews code with a Gemini model, through the Interactions API.
 *
 * <p>One chunk is one request. The API key travels in a header and only to the configured
 * origin; it is never part of an address, a log line or an error message. Redirects are not
 * followed. The answer is treated as untrusted input: it is read leniently into
 * {@link RawFinding}s and nothing in it is believed until the validator has checked it.
 *
 * <p>Nothing is retried and nothing waits. A failure is reported as a
 * {@link ProviderException} that says whether trying again later can help.
 */
@Component
@ConditionalOnProperty(name = "app.review.provider", havingValue = "gemini")
public class GeminiReviewProvider implements CodeReviewProvider {

	static final String NAME = "GEMINI";

	private static final Logger log = LoggerFactory.getLogger(GeminiReviewProvider.class);

	private static final String PATH = "/v1beta/interactions";

	private static final String API_KEY_HEADER = "x-goog-api-key";

	/** Codes the API uses when it refuses to generate an answer for the given content. */
	private static final Set<String> BLOCKED_CODES = Set.of("safety", "recitation", "language",
			"prohibited_content", "spii", "blocklist", "content_blocked");

	private static final String UNAVAILABLE = "The review model is not available right now";

	private static final String UNEXPECTED = "The review model returned an unexpected answer";

	/** A finding that cannot be read at all. It fails validation, so it is counted there. */
	private static final RawFinding UNREADABLE = new RawFinding(null, null, null, null, null, null, null, null,
			null, null);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final GeminiProperties properties;
	private final Clock clock;
	private final RestClient http;
	private final URI endpoint;

	@Autowired
	public GeminiReviewProvider(GeminiProperties properties) {
		this(properties, Clock.systemUTC());
	}

	GeminiReviewProvider(GeminiProperties properties, Clock clock) {
		if (!properties.hasApiKey()) {
			throw new IllegalStateException("GEMINI_API_KEY is not set. REVIEW_PROVIDER=gemini needs the key of a "
					+ "Google project without billing.");
		}
		this.properties = properties;
		this.clock = clock;
		this.endpoint = URI.create(properties.apiUrl() + PATH);
		HttpClient client = HttpClient.newBuilder()
				.connectTimeout(properties.connectTimeout())
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
		// Covers the whole exchange, the body included.
		requestFactory.setReadTimeout(properties.readTimeout());
		this.http = RestClient.builder().requestFactory(requestFactory).build();
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public String model() {
		return properties.model();
	}

	@Override
	public int promptVersion() {
		return GeminiPrompt.VERSION;
	}

	@Override
	public ProviderLimits limits() {
		return new ProviderLimits(properties.requestsPerMinute(), properties.charactersPerMinute());
	}

	/**
	 * @throws ProviderException if the model cannot be reached, refuses, or does not return
	 * the agreed JSON
	 */
	@Override
	public List<RawFinding> review(ReviewChunk chunk) {
		if (chunk.files().isEmpty()) {
			return List.of();
		}
		String input = GeminiPrompt.input(chunk);
		Answer answer = send(JSON.writeValueAsString(requestBody(input)));
		if (answer.tooLarge()) {
			log.warn("Gemini answered with more than {} bytes", properties.maxResponseSize().toBytes());
			throw new ProviderException(ProviderErrorCode.PROVIDER_INVALID_RESPONSE, UNEXPECTED);
		}
		if (answer.status() < 200 || answer.status() >= 300) {
			throw failure(answer);
		}
		return findings(answer, input.length());
	}

	private Map<String, Object> requestBody(String input) {
		Map<String, Object> format = new LinkedHashMap<>();
		format.put("type", "text");
		format.put("mime_type", "application/json");
		format.put("schema", GeminiPrompt.responseSchema());

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", properties.model());
		body.put("system_instruction", GeminiPrompt.SYSTEM_INSTRUCTION);
		body.put("input", input);
		body.put("response_format", format);
		// Without this the API keeps every request and answer on its side.
		body.put("store", false);
		body.put("generation_config", Map.of("max_output_tokens", properties.maxOutputTokens()));
		return body;
	}

	private Answer send(String body) {
		int limit = Math.toIntExact(properties.maxResponseSize().toBytes());
		try {
			return http.post()
					.uri(endpoint)
					.contentType(MediaType.APPLICATION_JSON)
					.header(API_KEY_HEADER, properties.apiKey())
					.body(body)
					.exchange((request, response) -> {
						// One byte more than allowed is enough to know the answer is too large.
						byte[] bytes = response.getBody().readNBytes(limit + 1);
						return new Answer(response.getStatusCode().value(), response.getHeaders(), bytes,
								bytes.length > limit);
					});
		}
		catch (RestClientException ex) {
			// No connection, a timeout or a dropped connection.
			log.warn("Request to Gemini failed: {}", ex.toString());
			throw new ProviderException(ProviderErrorCode.PROVIDER_UNAVAILABLE, UNAVAILABLE, ex);
		}
	}

	// A successful HTTP status says only that an interaction exists; its own status says how
	// the generation ended.
	private List<RawFinding> findings(Answer answer, int inputCharacters) {
		Map<?, ?> interaction;
		try {
			interaction = asMap(JSON.readValue(answer.body(), Object.class));
		}
		catch (JacksonException ex) {
			interaction = null;
		}
		if (interaction == null) {
			log.warn("Gemini answered with status {} and a body that is not a JSON object", answer.status());
			throw new ProviderException(ProviderErrorCode.PROVIDER_UNAVAILABLE, UNEXPECTED);
		}
		String status = asString(interaction.get("status"));
		Map<?, ?> usage = asMap(interaction.get("usage"));
		Object inputTokens = usage == null ? null : usage.get("total_input_tokens");
		Object outputTokens = usage == null ? null : usage.get("total_output_tokens");
		if ("incomplete".equals(status)) {
			log.warn("Gemini cut its answer off at the output limit ({} characters sent, {} input tokens, {} "
					+ "output tokens)", inputCharacters, inputTokens, outputTokens);
			throw new ProviderException(ProviderErrorCode.PROVIDER_OUTPUT_TRUNCATED,
					"The review model's answer was cut off at its output limit");
		}
		if (!"completed".equals(status)) {
			List<String> codes = errorCodes(interaction);
			log.warn("Gemini ended an interaction with status {} and codes {}", status, codes);
			if (codes.stream().anyMatch(BLOCKED_CODES::contains)) {
				throw blocked();
			}
			throw new ProviderException(ProviderErrorCode.PROVIDER_UNAVAILABLE, UNAVAILABLE);
		}
		log.info("Gemini reviewed {} characters: {} input tokens, {} output tokens", inputCharacters, inputTokens,
				outputTokens);
		return parseFindings(outputText(interaction));
	}

	/** The text of the model's answer: every text part of every output step, in order. */
	private static String outputText(Map<?, ?> interaction) {
		StringBuilder text = new StringBuilder();
		for (Object step : asList(interaction.get("steps"))) {
			Map<?, ?> stepMap = asMap(step);
			if (stepMap == null || !"model_output".equals(stepMap.get("type"))) {
				continue;
			}
			for (Object part : asList(stepMap.get("content"))) {
				Map<?, ?> partMap = asMap(part);
				if (partMap != null && "text".equals(partMap.get("type")) && partMap.get("text") instanceof String s) {
					text.append(s);
				}
			}
		}
		return text.toString();
	}

	private static List<RawFinding> parseFindings(String text) {
		Map<?, ?> root;
		try {
			root = text.isBlank() ? null : asMap(JSON.readValue(text, Object.class));
		}
		catch (JacksonException ex) {
			root = null;
		}
		if (root == null || !(root.get("findings") instanceof List<?> reported)) {
			log.warn("Gemini completed an interaction without the agreed JSON");
			throw new ProviderException(ProviderErrorCode.PROVIDER_INVALID_RESPONSE, UNEXPECTED);
		}
		List<RawFinding> findings = new ArrayList<>(reported.size());
		for (Object item : reported) {
			findings.add(toRawFinding(item));
		}
		return List.copyOf(findings);
	}

	// A finding with one field that cannot be read is not read at all. Reading the rest would
	// turn a wrong value into a missing one, and a missing value can be a valid finding.
	private static RawFinding toRawFinding(Object item) {
		Map<?, ?> fields = asMap(item);
		if (fields == null) {
			return UNREADABLE;
		}
		try {
			return new RawFinding(enumValue(FindingKind.class, fields.get("kind")),
					enumValue(FindingCategory.class, fields.get("category")),
					enumValue(Severity.class, fields.get("severity")), string(fields.get("filePath")),
					integer(fields.get("lineStart")), integer(fields.get("lineEnd")), string(fields.get("title")),
					string(fields.get("explanation")), string(fields.get("suggestion")),
					string(fields.get("suggestedCode")));
		}
		catch (IllegalArgumentException ex) {
			return UNREADABLE;
		}
	}

	private static <E extends Enum<E>> E enumValue(Class<E> type, Object value) {
		String name = string(value);
		// Throws for a name that is not one of the constants.
		return name == null ? null : Enum.valueOf(type, name);
	}

	private static String string(Object value) {
		if (value == null || value instanceof String) {
			return (String) value;
		}
		throw new IllegalArgumentException("not a string");
	}

	private static Integer integer(Object value) {
		if (value == null || value instanceof Integer) {
			return (Integer) value;
		}
		throw new IllegalArgumentException("not an integer");
	}

	private ProviderException failure(Answer answer) {
		int status = answer.status();
		ErrorBody error = ErrorBody.read(answer.body());
		// Before anything else: a rejected key arrives as a plain 400, which would otherwise
		// look like a mistake in the request.
		if (status == 401 || status == 403 || error.isAbout("API_KEY_INVALID", "authentication",
				"permission_denied", "UNAUTHENTICATED", "PERMISSION_DENIED")) {
			log.error("Gemini rejected the configured GEMINI_API_KEY (status {}). Replace it.", status);
			return new ProviderException(ProviderErrorCode.PROVIDER_AUTH_FAILED,
					"The review model rejected the configured API key");
		}
		if (status == 429) {
			if (error.isAbout("quota_exceeded")) {
				return new ProviderException(ProviderErrorCode.PROVIDER_QUOTA_EXHAUSTED,
						"The daily quota of the review model is used up. Try again tomorrow");
			}
			return new ProviderException(ProviderErrorCode.PROVIDER_RATE_LIMITED,
					"The review model's rate limit was reached. Try again later", retryAt(answer.headers()));
		}
		if (error.codes().stream().anyMatch(BLOCKED_CODES::contains)) {
			return blocked();
		}
		boolean requestRejected = status == 501 || (status >= 400 && status < 500 && status != 408);
		if (requestRejected) {
			// The same request will be rejected again: an unknown model, a bad parameter.
			log.error("Gemini rejected the request with status {} and codes {}: {}", status, error.codes(),
					error.message());
			return new ProviderException(ProviderErrorCode.PROVIDER_MISCONFIGURED,
					"The review model rejected the request. Check the configured model");
		}
		// 408, 5xx, a redirect, or a status that makes no sense here.
		log.warn("Gemini answered with status {} and codes {}", status, error.codes());
		return new ProviderException(ProviderErrorCode.PROVIDER_UNAVAILABLE, UNAVAILABLE);
	}

	private static ProviderException blocked() {
		return new ProviderException(ProviderErrorCode.PROVIDER_BLOCKED,
				"The review model refused to review this content");
	}

	private Instant retryAt(HttpHeaders headers) {
		String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
		if (value == null || !value.strip().matches("\\d{1,9}")) {
			return null;
		}
		return clock.instant().plusSeconds(Long.parseLong(value.strip()));
	}

	/** Codes of an interaction that did not complete, wherever the API put them. */
	private static List<String> errorCodes(Map<?, ?> interaction) {
		List<String> codes = new ArrayList<>();
		addCode(codes, interaction.get("error"));
		for (Object error : asList(interaction.get("errors"))) {
			addCode(codes, error);
		}
		return codes;
	}

	private static void addCode(List<String> codes, Object error) {
		Map<?, ?> map = asMap(error);
		if (map != null && map.get("code") instanceof String code) {
			codes.add(code);
		}
	}

	private static Map<?, ?> asMap(Object value) {
		return value instanceof Map<?, ?> map ? map : null;
	}

	private static List<?> asList(Object value) {
		return value instanceof List<?> list ? list : List.of();
	}

	private static String asString(Object value) {
		return value instanceof String s ? s : null;
	}

	private record Answer(int status, HttpHeaders headers, byte[] body, boolean tooLarge) {
	}

	/**
	 * What an error answer says. The API has two shapes for it: an object whose {@code code}
	 * is a word, and, for a rejected key, an array around an object whose {@code code} is the
	 * HTTP status and whose meaning is in {@code status} and in the reasons of its details.
	 * Anything else gives no codes.
	 */
	private record ErrorBody(List<String> codes, String message) {

		private static final int MAX_MESSAGE_LENGTH = 300;

		static ErrorBody read(byte[] body) {
			Object root;
			try {
				root = JSON.readValue(body, Object.class);
			}
			catch (JacksonException ex) {
				return new ErrorBody(List.of(), null);
			}
			if (root instanceof List<?> list && !list.isEmpty()) {
				root = list.get(0);
			}
			Map<?, ?> wrapper = asMap(root);
			Map<?, ?> error = wrapper == null ? null : asMap(wrapper.get("error"));
			if (error == null) {
				return new ErrorBody(List.of(), null);
			}
			List<String> codes = new ArrayList<>();
			if (error.get("code") instanceof String code) {
				codes.add(code);
			}
			if (error.get("status") instanceof String status) {
				codes.add(status);
			}
			for (Object detail : asList(error.get("details"))) {
				Map<?, ?> detailMap = asMap(detail);
				if (detailMap != null && detailMap.get("reason") instanceof String reason) {
					codes.add(reason);
				}
			}
			String message = asString(error.get("message"));
			if (message != null && message.length() > MAX_MESSAGE_LENGTH) {
				message = message.substring(0, MAX_MESSAGE_LENGTH);
			}
			return new ErrorBody(List.copyOf(codes), message);
		}

		boolean isAbout(String... names) {
			return codes.stream().anyMatch(code -> List.of(names).contains(code));
		}

	}

}
