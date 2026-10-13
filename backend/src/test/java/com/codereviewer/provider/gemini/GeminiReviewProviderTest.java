package com.codereviewer.provider.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import com.codereviewer.github.SourceFile;
import com.codereviewer.provider.FindingCategory;
import com.codereviewer.provider.FindingKind;
import com.codereviewer.provider.FindingValidator;
import com.codereviewer.provider.NumberedSource;
import com.codereviewer.provider.ProviderErrorCode;
import com.codereviewer.provider.ProviderException;
import com.codereviewer.provider.ProviderLimits;
import com.codereviewer.provider.RawFinding;
import com.codereviewer.provider.ReviewChunk;
import com.codereviewer.provider.Severity;
import com.codereviewer.provider.ValidationResult;
import com.codereviewer.testsupport.FakeHttpServer;
import com.codereviewer.testsupport.FakeHttpServer.Request;
import com.codereviewer.testsupport.FakeHttpServer.Response;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class GeminiReviewProviderTest {

	private static final String PATH = "/v1beta/interactions";
	private static final String API_KEY = "AIza-test-key-that-must-never-be-printed";
	private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** The two files the recorded answers were given for. */
	private static final ReviewChunk CHUNK = new ReviewChunk(List.of(
			new SourceFile("src/UserDao.java", "line\n".repeat(18)),
			new SourceFile("src/Money.java", "line\n".repeat(8))));

	private final FakeHttpServer gemini = new FakeHttpServer();
	private final TestGeminiProperties settings = new TestGeminiProperties();

	GeminiReviewProviderTest() {
		settings.apiUrl = gemini.origin();
		settings.apiKey = API_KEY;
		gemini.on(PATH, recorded(200, "completed.json"));
	}

	@AfterEach
	void stopServer() {
		gemini.close();
	}

	// the request

	@Test
	void sendsOnePostWithTheModelTheInstructionsTheCodeAndTheSchema() {
		settings.model = "gemini-3.8-flash";
		settings.maxOutputTokens = 1234;

		provider().review(CHUNK);

		assertThat(gemini.requests()).hasSize(1);
		Request request = gemini.requests().getFirst();
		assertThat(request.method()).isEqualTo("POST");
		assertThat(request.rawPath()).isEqualTo(PATH);
		assertThat(request.header("Content-Type")).startsWith("application/json");
		Map<?, ?> body = JSON.readValue(request.body(), Map.class);
		assertThat(body.get("model")).isEqualTo("gemini-3.8-flash");
		assertThat(body.get("system_instruction")).isEqualTo(GeminiPrompt.SYSTEM_INSTRUCTION);
		assertThat(body.get("input")).isEqualTo(NumberedSource.of(CHUNK));
		assertThat(body.get("response_format")).isEqualTo(Map.of("type", "text", "mime_type", "application/json",
				"schema", GeminiPrompt.responseSchema()));
		assertThat(body.get("generation_config")).isEqualTo(Map.of("max_output_tokens", 1234));
	}

	@Test
	void asksTheApiNotToStoreTheInteraction() {
		provider().review(CHUNK);

		assertThat(JSON.readValue(gemini.requests().getFirst().body(), Map.class)).containsEntry("store", false);
	}

	@Test
	void theKeyTravelsInAHeaderAndNeverInTheAddressTheBodyOrTheLog(CapturedOutput output) {
		provider().review(CHUNK);

		Request request = gemini.requests().getFirst();
		assertThat(request.header("x-goog-api-key")).isEqualTo(API_KEY);
		assertThat(request.rawQuery()).isNull();
		assertThat(request.rawPath()).doesNotContain(API_KEY);
		assertThat(request.body()).doesNotContain(API_KEY);
		assertThat(output.getAll()).doesNotContain(API_KEY);
	}

	@Test
	void codeWithLettersOutsideAsciiReachesTheApiAsUtf8() {
		ReviewChunk chunk = new ReviewChunk(List.of(new SourceFile("src/Račun.java",
				"// Provjera: č, ć, š, đ, ž, Č, Ć, Š, Đ, Ž\nString poruka = \"Greška — iznos je 5 €, 日本語 😀\";\n")));

		provider().review(chunk);

		// The stand-in decodes the body as UTF-8, so any other encoding would garble it.
		Request request = gemini.requests().getFirst();
		assertThat(request.header("Content-Type")).isIn("application/json", "application/json;charset=UTF-8");
		assertThat(JSON.readValue(request.body(), Map.class).get("input")).isEqualTo(NumberedSource.of(chunk))
				.asString()
				.contains("=== FILE: src/Račun.java (2 lines) ===")
				.contains("1| // Provjera: č, ć, š, đ, ž, Č, Ć, Š, Đ, Ž")
				.contains("Greška — iznos je 5 €, 日本語 😀");
	}

	@Test
	void answerWithLettersOutsideAsciiIsReadAsUtf8() {
		ReviewChunk chunk = new ReviewChunk(List.of(new SourceFile("src/Račun.java", "int x = 1;\n")));
		gemini.on(PATH, completed("""
				{"findings": [{"kind": "ISSUE", "category": "MAINTAINABILITY", "severity": "LOW",
				  "filePath": "src/Račun.java", "lineStart": 1, "lineEnd": 1, "title": "Nejasno ime: šifra č ć đ ž",
				  "explanation": "Costs 5 € — 日本語 😀", "suggestion": null, "suggestedCode": null}]}"""));

		assertThat(provider().review(chunk)).singleElement().satisfies(finding -> {
			assertThat(finding.filePath()).isEqualTo("src/Račun.java");
			assertThat(finding.title()).isEqualTo("Nejasno ime: šifra č ć đ ž");
			assertThat(finding.explanation()).isEqualTo("Costs 5 € — 日本語 😀");
		});
	}

	@Test
	void emptyChunkIsNotSentAtAll() {
		assertThat(provider().review(new ReviewChunk(List.of()))).isEmpty();
		assertThat(gemini.requests()).isEmpty();
	}

	@Test
	void providerRefusesToStartWithoutAKey() {
		settings.apiKey = " ";

		assertThatThrownBy(this::provider).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("GEMINI_API_KEY is not set");
	}

	@Test
	void nameModelAndPromptVersionIdentifyTheProvider() {
		settings.model = "gemini-3.8-flash";

		GeminiReviewProvider provider = provider();

		assertThat(provider.name()).isEqualTo("GEMINI");
		assertThat(provider.model()).isEqualTo("gemini-3.8-flash");
		assertThat(provider.promptVersion()).isEqualTo(GeminiPrompt.VERSION);
	}

	@Test
	void limitsComeFromTheSettings() {
		settings.requestsPerMinute = 7;
		settings.charactersPerMinute = 123_456;

		assertThat(provider().limits()).isEqualTo(new ProviderLimits(7, 123_456));
		assertThat(gemini.requests()).isEmpty();
	}

	// answers recorded from the real API

	@Test
	void recordedAnswerIsReadIntoFindings() {
		List<RawFinding> findings = provider().review(CHUNK);

		assertThat(findings).hasSize(5);
		RawFinding first = findings.getFirst();
		assertThat(first.kind()).isEqualTo(FindingKind.ISSUE);
		assertThat(first.category()).isEqualTo(FindingCategory.SECURITY);
		assertThat(first.severity()).isEqualTo(Severity.CRITICAL);
		assertThat(first.filePath()).isEqualTo("src/UserDao.java");
		assertThat(first.lineStart()).isEqualTo(15);
		assertThat(first.lineEnd()).isEqualTo(15);
		assertThat(first.title()).isNotBlank();
		assertThat(first.explanation()).isNotBlank();
		assertThat(first.suggestion()).isNotBlank();
		RawFinding last = findings.getLast();
		assertThat(last.kind()).isEqualTo(FindingKind.GOOD_PRACTICE);
		assertThat(last.severity()).isNull();
		assertThat(last.filePath()).isNull();
		assertThat(last.lineStart()).isNull();
		assertThat(last.lineEnd()).isNull();
	}

	@Test
	void everyFindingOfTheRecordedAnswerPassesValidation() {
		try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
			ValidationResult result = new FindingValidator(factory.getValidator()).validate(CHUNK,
					provider().review(CHUNK));

			assertThat(result.discarded()).isZero();
			assertThat(result.accepted()).hasSize(5);
		}
	}

	@Test
	void recordedAnswerThatWasCutOffIsNotUsed(CapturedOutput output) {
		gemini.on(PATH, recorded(200, "incomplete.json"));

		assertFailsWith(ProviderErrorCode.PROVIDER_OUTPUT_TRUNCATED, () -> provider().review(CHUNK));
		assertThat(output.getAll()).contains("cut its answer off").contains("838 input tokens");
	}

	@Test
	void recordedAnswerToAnInvalidKeyIsAnAuthenticationFailureAlthoughItsStatusIs400(CapturedOutput output) {
		gemini.on(PATH, recorded(400, "error-invalid-key.json"));

		assertThatThrownBy(() -> provider().review(CHUNK)).isInstanceOfSatisfying(ProviderException.class, ex -> {
			assertThat(ex.getCode()).isEqualTo(ProviderErrorCode.PROVIDER_AUTH_FAILED);
			assertThat(ex.isRetryable()).isFalse();
			assertThat(ex.getMessage()).doesNotContain(API_KEY);
		});
		assertThat(output.getAll()).contains("GEMINI_API_KEY").doesNotContain(API_KEY);
	}

	@Test
	void recordedAnswerToAnUnknownModelIsAConfigurationError(CapturedOutput output) {
		gemini.on(PATH, recorded(404, "error-model-not-found.json"));

		assertFailsWith(ProviderErrorCode.PROVIDER_MISCONFIGURED, () -> provider().review(CHUNK));
		assertThat(output.getAll()).contains("not_found").contains("gemini-does-not-exist");
	}

	@Test
	void recordedAnswerToAnInvalidRequestIsAConfigurationError() {
		gemini.on(PATH, recorded(400, "error-invalid-request.json"));

		assertFailsWith(ProviderErrorCode.PROVIDER_MISCONFIGURED, () -> provider().review(CHUNK));
	}

	// failures the API could not be made to produce; bodies follow its documentation

	@ParameterizedTest(name = "status {0} with code {1} is {2}")
	@CsvSource({
			"401, authentication,      PROVIDER_AUTH_FAILED",
			"403, permission_denied,   PROVIDER_AUTH_FAILED",
			"403, ,                    PROVIDER_AUTH_FAILED",
			"400, authentication,      PROVIDER_AUTH_FAILED",
			"429, rate_limit_exceeded, PROVIDER_RATE_LIMITED",
			"429, too_many_requests,   PROVIDER_RATE_LIMITED",
			"429, something_new,       PROVIDER_RATE_LIMITED",
			"429, ,                    PROVIDER_RATE_LIMITED",
			"429, quota_exceeded,      PROVIDER_QUOTA_EXHAUSTED",
			"400, safety,              PROVIDER_BLOCKED",
			"400, recitation,          PROVIDER_BLOCKED",
			"400, prohibited_content,  PROVIDER_BLOCKED",
			"400, invalid_request,     PROVIDER_MISCONFIGURED",
			"400, parameter_unknown,   PROVIDER_MISCONFIGURED",
			"400, ,                    PROVIDER_MISCONFIGURED",
			"402, payment_required,    PROVIDER_MISCONFIGURED",
			"404, model_not_found,     PROVIDER_MISCONFIGURED",
			"416, out_of_range,        PROVIDER_MISCONFIGURED",
			"501, unimplemented,       PROVIDER_MISCONFIGURED",
			"408, ,                    PROVIDER_UNAVAILABLE",
			"500, api_error,           PROVIDER_UNAVAILABLE",
			"502, ,                    PROVIDER_UNAVAILABLE",
			"503, service_unavailable, PROVIDER_UNAVAILABLE",
			"504, deadline_exceeded,   PROVIDER_UNAVAILABLE" })
	void errorStatusBecomesItsCode(int status, String code, ProviderErrorCode expected) {
		gemini.on(PATH, code == null ? Response.status(status) : error(status, code));

		assertFailsWith(expected, () -> provider().review(CHUNK));
		assertThat(gemini.requests()).hasSize(1);
	}

	@Test
	void errorBodyInTheOlderShapeIsUnderstoodToo() {
		gemini.on(PATH, Response.json(429, """
				[{"error": {"code": 429, "message": "Quota exceeded.", "status": "RESOURCE_EXHAUSTED"}}]"""));

		assertFailsWith(ProviderErrorCode.PROVIDER_RATE_LIMITED, () -> provider().review(CHUNK));
	}

	@ParameterizedTest
	@ValueSource(strings = { "not json at all", "[]", "{}", "{\"error\": \"text\"}", "{\"error\": {\"code\": 7}}" })
	void errorBodyThatSaysNothingLeavesTheStatusToDecide(String body) {
		gemini.on(PATH, Response.json(503, body));

		assertFailsWith(ProviderErrorCode.PROVIDER_UNAVAILABLE, () -> provider().review(CHUNK));
	}

	@Test
	void rateLimitCarriesTheTimeToTryAgainWhenTheApiGivesOne() {
		gemini.on(PATH, error(429, "rate_limit_exceeded").header("Retry-After", "30"));

		assertThatThrownBy(() -> provider().review(CHUNK)).isInstanceOfSatisfying(ProviderException.class, ex -> {
			assertThat(ex.getCode()).isEqualTo(ProviderErrorCode.PROVIDER_RATE_LIMITED);
			assertThat(ex.isRetryable()).isTrue();
			assertThat(ex.getRetryAt()).contains(NOW.plusSeconds(30));
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "soon", "-5", "", "Wed, 21 Oct 2026 07:28:00 GMT" })
	void rateLimitWithoutAUsableTimeCarriesNone(String retryAfter) {
		gemini.on(PATH, error(429, "rate_limit_exceeded").header("Retry-After", retryAfter));

		assertThatThrownBy(() -> provider().review(CHUNK)).isInstanceOfSatisfying(ProviderException.class,
				ex -> assertThat(ex.getRetryAt()).isEmpty());
	}

	@Test
	void redirectIsNotFollowed() {
		try (FakeHttpServer elsewhere = new FakeHttpServer()) {
			elsewhere.on(PATH, recorded(200, "completed.json"));
			gemini.on(PATH, Response.redirect(307, elsewhere.origin() + PATH));

			assertFailsWith(ProviderErrorCode.PROVIDER_UNAVAILABLE, () -> provider().review(CHUNK));
			assertThat(elsewhere.requests()).isEmpty();
		}
	}

	@Test
	void answerThatTakesLongerThanTheTimeoutIsUnavailable() {
		settings.readTimeout = Duration.ofMillis(300);
		gemini.on(PATH, recorded(200, "completed.json").after(Duration.ofSeconds(3)));

		assertThatThrownBy(() -> provider().review(CHUNK)).isInstanceOfSatisfying(ProviderException.class, ex -> {
			assertThat(ex.getCode()).isEqualTo(ProviderErrorCode.PROVIDER_UNAVAILABLE);
			assertThat(ex.isRetryable()).isTrue();
			assertThat(ex.getCause()).isNotNull();
		});
	}

	@Test
	void serverThatIsNotThereIsUnavailable() {
		gemini.close();

		assertFailsWith(ProviderErrorCode.PROVIDER_UNAVAILABLE, () -> provider().review(CHUNK));
	}

	@Test
	void answerLargerThanTheLimitIsNotRead() {
		settings.maxResponseSize = DataSize.ofKilobytes(1);
		gemini.on(PATH, completed("{\"findings\": [], \"padding\": \"" + "x".repeat(5000) + "\"}"));

		assertFailsWith(ProviderErrorCode.PROVIDER_INVALID_RESPONSE, () -> provider().review(CHUNK));
	}

	// interactions that did not complete

	@Test
	void interactionBlockedForItsContentIsBlocked() {
		gemini.on(PATH, Response.json(200, """
				{"status": "failed", "error": {"code": "safety", "message": "Blocked."}, "steps": []}"""));

		assertFailsWith(ProviderErrorCode.PROVIDER_BLOCKED, () -> provider().review(CHUNK));
	}

	@Test
	void interactionBlockedWithItsCodeInAListIsBlocked() {
		gemini.on(PATH, Response.json(200, """
				{"status": "failed", "errors": [{"code": "recitation", "message": "Blocked."}]}"""));

		assertFailsWith(ProviderErrorCode.PROVIDER_BLOCKED, () -> provider().review(CHUNK));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"status\": \"failed\"}",
			"{\"status\": \"failed\", \"error\": {\"code\": \"api_error\"}}",
			"{\"status\": \"cancelled\"}",
			"{\"status\": \"in_progress\"}",
			"{\"status\": 7}",
			"{\"steps\": []}",
			"[]",
			"\"completed\"",
			"not json" })
	void interactionThatDidNotCompleteOrCannotBeReadIsUnavailable(String body) {
		gemini.on(PATH, Response.json(200, body));

		assertFailsWith(ProviderErrorCode.PROVIDER_UNAVAILABLE, () -> provider().review(CHUNK));
	}

	// completed interactions whose text is not what was agreed

	@ParameterizedTest
	@ValueSource(strings = {
			"",
			"   ",
			"Here are the findings you asked for.",
			"{\"findings\": [",
			"[]",
			"{}",
			"{\"findings\": null}",
			"{\"findings\": \"none\"}",
			"{\"findings\": {\"kind\": \"ISSUE\"}}",
			"{\"result\": []}" })
	void completedInteractionWithoutTheAgreedJsonIsAnInvalidResponse(String text) {
		gemini.on(PATH, completed(text));

		assertThatThrownBy(() -> provider().review(CHUNK)).isInstanceOfSatisfying(ProviderException.class, ex -> {
			assertThat(ex.getCode()).isEqualTo(ProviderErrorCode.PROVIDER_INVALID_RESPONSE);
			assertThat(ex.isRetryable()).isTrue();
		});
	}

	@Test
	void completedInteractionWithoutAnOutputStepIsAnInvalidResponse() {
		gemini.on(PATH, Response.json(200, """
				{"status": "completed", "steps": [{"type": "thought", "signature": "x"}]}"""));

		assertFailsWith(ProviderErrorCode.PROVIDER_INVALID_RESPONSE, () -> provider().review(CHUNK));
	}

	@Test
	void noFindingsIsAValidAnswer() {
		gemini.on(PATH, completed("{\"findings\": []}"));

		assertThat(provider().review(CHUNK)).isEmpty();
	}

	@Test
	void textSplitOverSeveralPartsIsReadAsOne() {
		gemini.on(PATH, Response.json(200, """
				{"status": "completed", "steps": [
				  {"type": "thought", "signature": "x"},
				  {"type": "model_output", "content": [
				    {"type": "text", "text": "{\\"findings\\": [{\\"kind\\": \\"GOOD_PRACTICE\\","},
				    {"type": "image", "data": "ignored"},
				    {"type": "text", "text": " \\"category\\": \\"SECURITY\\", \\"title\\": \\"T\\"}]}"}]}]}"""));

		assertThat(provider().review(CHUNK)).singleElement().satisfies(finding -> {
			assertThat(finding.kind()).isEqualTo(FindingKind.GOOD_PRACTICE);
			assertThat(finding.category()).isEqualTo(FindingCategory.SECURITY);
			assertThat(finding.title()).isEqualTo("T");
		});
	}

	@Test
	void fieldsThatAreLeftOutAreReadAsMissing() {
		gemini.on(PATH, completed("""
				{"findings": [{"kind": "GOOD_PRACTICE", "category": "PERFORMANCE", "title": "T", "explanation": "E"}]}"""));

		assertThat(provider().review(CHUNK)).containsExactly(new RawFinding(FindingKind.GOOD_PRACTICE,
				FindingCategory.PERFORMANCE, null, null, null, null, "T", "E", null, null));
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', value = {
			"an unknown kind         | {\"kind\": \"PRAISE\", \"category\": \"SECURITY\", \"title\": \"T\"}",
			"an unknown category     | {\"kind\": \"ISSUE\", \"category\": \"STYLE\", \"title\": \"T\"}",
			"an unknown severity     | {\"kind\": \"GOOD_PRACTICE\", \"category\": \"SECURITY\", \"severity\": \"INFO\", \"title\": \"T\"}",
			"a kind in lower case    | {\"kind\": \"issue\", \"category\": \"SECURITY\", \"title\": \"T\"}",
			"a line given as text    | {\"kind\": \"GOOD_PRACTICE\", \"category\": \"SECURITY\", \"title\": \"T\", \"lineStart\": \"3\"}",
			"a line with a fraction  | {\"kind\": \"GOOD_PRACTICE\", \"category\": \"SECURITY\", \"title\": \"T\", \"lineEnd\": 3.5}",
			"a line beyond an int    | {\"kind\": \"GOOD_PRACTICE\", \"category\": \"SECURITY\", \"title\": \"T\", \"lineEnd\": 99999999999}",
			"a title that is a number| {\"kind\": \"GOOD_PRACTICE\", \"category\": \"SECURITY\", \"title\": 42}",
			"a path that is a list   | {\"kind\": \"GOOD_PRACTICE\", \"category\": \"SECURITY\", \"title\": \"T\", \"filePath\": [\"a\"]}",
			"not an object           | \"ISSUE\"",
			"null                    | null" })
	void findingWithAFieldThatCannotBeReadIsKeptAsUnreadableForTheValidatorToCount(String description, String item) {
		String valid = "{\"kind\": \"GOOD_PRACTICE\", \"category\": \"SECURITY\", \"title\": \"Valid\"}";
		gemini.on(PATH, completed("{\"findings\": [" + item + ", " + valid + "]}"));

		List<RawFinding> findings = provider().review(CHUNK);

		assertThat(findings).hasSize(2);
		assertThat(findings.getFirst())
				.isEqualTo(new RawFinding(null, null, null, null, null, null, null, null, null, null));
		assertThat(findings.getLast().title()).isEqualTo("Valid");
		try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
			ValidationResult result = new FindingValidator(factory.getValidator()).validate(CHUNK, findings);
			assertThat(result.accepted()).hasSize(1);
			assertThat(result.discarded()).isEqualTo(1);
		}
	}

	@Test
	void fieldsTheSchemaDoesNotKnowAreIgnored() {
		gemini.on(PATH, completed("""
				{"findings": [{"kind": "GOOD_PRACTICE", "category": "SECURITY", "title": "T", "score": 100,
				  "codeSnippet": "rm -rf /"}], "overallScore": 100}"""));

		assertThat(provider().review(CHUNK)).singleElement()
				.satisfies(finding -> assertThat(finding.title()).isEqualTo("T"));
	}

	@Test
	void failureNeverRepeatsTheRequest() {
		gemini.on(PATH, error(503, "service_unavailable"));

		assertFailsWith(ProviderErrorCode.PROVIDER_UNAVAILABLE, () -> provider().review(CHUNK));
		assertThat(gemini.requests()).hasSize(1);
	}

	private GeminiReviewProvider provider() {
		return new GeminiReviewProvider(settings.build(), Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static void assertFailsWith(ProviderErrorCode code, ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOfSatisfying(ProviderException.class, ex -> {
			assertThat(ex.getCode()).isEqualTo(code);
			assertThat(ex.getMessage()).doesNotContain(API_KEY);
		});
	}

	/** An answer recorded from the real API, see src/test/resources/gemini. */
	private static Response recorded(int status, String file) {
		try (InputStream in = GeminiReviewProviderTest.class.getResourceAsStream("/gemini/" + file)) {
			return Response.json(status, new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** A completed interaction, shaped like the recorded one, with the given text as its output. */
	private static Response completed(String text) {
		Map<String, Object> interaction = Map.of("status", "completed", "usage",
				Map.of("total_input_tokens", 10, "total_output_tokens", 5), "steps",
				List.of(Map.of("type", "thought", "signature", "c2FuaXRpemVk"),
						Map.of("type", "model_output", "content", List.of(Map.of("type", "text", "text", text)))));
		return Response.json(200, JSON.writeValueAsString(interaction));
	}

	private static Response error(int status, String code) {
		return Response.json(status, "{\"error\":{\"message\":\"Message for " + code + "\",\"code\":\"" + code + "\"}}");
	}

}
