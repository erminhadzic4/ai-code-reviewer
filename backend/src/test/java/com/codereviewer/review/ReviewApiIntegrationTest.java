package com.codereviewer.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import com.codereviewer.IntegrationTest;
import com.codereviewer.auth.JwtService;
import com.codereviewer.github.RepoRef;
import com.codereviewer.github.TestZip;
import com.codereviewer.provider.ProviderErrorCode;
import com.codereviewer.provider.ProviderException;
import com.codereviewer.review.job.ReviewJobStore;
import com.codereviewer.review.job.ReviewWorker;
import com.codereviewer.testsupport.FailingReviewProvider;
import com.codereviewer.testsupport.FakeHttpServer;
import com.codereviewer.testsupport.FakeHttpServer.Response;
import com.codereviewer.testsupport.GitHubStubs;
import com.codereviewer.testsupport.MutableClock;
import com.codereviewer.user.User;
import com.codereviewer.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The three review endpoints as a client sees them. The worker's timer is off, so a review
 * moves only when a test runs a round of the worker.
 */
@IntegrationTest
class ReviewApiIntegrationTest {

	private static final RepoRef REPO = new RepoRef("octo", "demo");
	private static final String REPO_URL = "https://github.com/octo/demo";
	private static final String SHA = "0123abc0123abc0123abc0123abc0123abc0123a";
	private static final String NOT_FOUND = "Review not found";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ReviewWorker worker;

	@Autowired
	private ReviewJobStore store;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private FakeHttpServer github;

	@Autowired
	private FailingReviewProvider provider;

	@Autowired
	private MutableClock clock;

	private long userId;
	private String token;

	@BeforeEach
	void startClean() {
		deleteReviewsAndAnalyses();
		github.reset();
		provider.reset();
		userId = newUser();
		token = jwtService.issue(userId);
	}

	@AfterEach
	void deleteReviewsAndAnalyses() {
		jdbcTemplate.update("DELETE FROM reviews");
		jdbcTemplate.update("DELETE FROM analyses");
	}

	// submitting

	@Test
	void submittingQueuesAReviewAndSaysWhereToFollowIt() throws Exception {
		String body = submit(token, REPO_URL).andExpect(status().isAccepted())
				.andExpect(header().exists("Location"))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.id").isNotEmpty())
				.andExpect(jsonPath("$.repo").value("octo/demo"))
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.stage").value((Object) null))
				.andExpect(jsonPath("$.branch").value((Object) null))
				.andExpect(jsonPath("$.commitSha").value((Object) null))
				.andExpect(jsonPath("$.chunksDone").value(0))
				.andExpect(jsonPath("$.chunksTotal").value(0))
				.andExpect(jsonPath("$.attempt").value(0))
				.andExpect(jsonPath("$.maxAttempts").value(3))
				.andExpect(jsonPath("$.retryAt").value((Object) null))
				.andExpect(jsonPath("$.cacheHit").value(false))
				.andExpect(jsonPath("$.createdAt").isNotEmpty())
				.andExpect(jsonPath("$.startedAt").value((Object) null))
				.andExpect(jsonPath("$.finishedAt").value((Object) null))
				.andExpect(jsonPath("$.error").value((Object) null))
				.andExpect(jsonPath("$.length()").value(16))
				.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(body, "$.id");

		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM reviews WHERE id = ?", UUID.fromString(id));
		assertThat(row).containsEntry("user_id", userId)
				.containsEntry("repo_owner", "octo")
				.containsEntry("repo_name", "demo")
				.containsEntry("status", "QUEUED");
	}

	@Test
	void locationIsTheAddressOfTheStatus() throws Exception {
		String location = submit(token, REPO_URL).andExpect(status().isAccepted())
				.andReturn().getResponse().getHeader("Location");

		assertThat(location).matches("/api/reviews/[0-9a-f-]{36}");
		mockMvc.perform(get(location).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("QUEUED"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "github.com/Octo/Demo", "https://www.github.com/octo/demo.git",
			"  http://github.com/octo/demo/ " })
	void everyAcceptedSpellingOfARepositoryIsStoredTheSameWay(String url) throws Exception {
		submit(token, url).andExpect(status().isAccepted()).andExpect(jsonPath("$.repo").value("octo/demo"));
	}

	@Test
	void submittingDoesNotAskGitHubAndDoesNotRunTheReview() throws Exception {
		submit(token, REPO_URL).andExpect(status().isAccepted());

		assertThat(github.requests()).isEmpty();
		assertThat(provider.received()).isEmpty();
	}

	@Test
	void sameRepositoryCanBeSubmittedAgain() throws Exception {
		String first = idOf(submit(token, REPO_URL));
		String second = idOf(submit(token, REPO_URL));

		assertThat(first).isNotEqualTo(second);
		assertThat(count("reviews")).isEqualTo(2);
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://gitlab.com/octo/demo", "https://github.com/octo", "octo/demo",
			"https://github.com/octo/demo/tree/main", "https://evil.example/github.com/octo/demo",
			"https://github.com@evil.example/octo/demo" })
	void addressThatIsNotAGitHubRepositoryIsRejected(String url) throws Exception {
		submit(token, url).andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.detail")
						.value("Enter a GitHub repository URL in the form https://github.com/owner/repository"));

		assertThat(count("reviews")).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"repoUrl\": null}", "{\"repoUrl\": \"\"}", "{\"repoUrl\": \"   \"}" })
	void missingAddressIsAValidationError(String json) throws Exception {
		mockMvc.perform(post("/api/reviews").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Validation failed"))
				.andExpect(jsonPath("$.errors.length()").value(1))
				.andExpect(jsonPath("$.errors[0].field").value("repoUrl"))
				.andExpect(jsonPath("$.errors[0].message").value("is required"));

		assertThat(count("reviews")).isZero();
	}

	@Test
	void bodyThatIsNotJsonIsRejected() throws Exception {
		mockMvc.perform(post("/api/reviews").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{not json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Malformed request body"));
	}

	@Test
	void tokenOfAUserThatNoLongerExistsCannotSubmit() throws Exception {
		submit(jwtService.issue(987_654_321_012L), REPO_URL).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.detail").value("Authentication required"));

		assertThat(count("reviews")).isZero();
	}

	// access

	@Test
	void everyEndpointNeedsAToken() throws Exception {
		String id = idOf(submit(token, REPO_URL));

		mockMvc.perform(post("/api/reviews").contentType(MediaType.APPLICATION_JSON)
				.content("{\"repoUrl\": \"" + REPO_URL + "\"}")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/reviews/" + id)).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/reviews/" + id + "/result")).andExpect(status().isUnauthorized());
		assertThat(count("reviews")).isEqualTo(1);
	}

	@Test
	void reviewOfAnotherUserLooksExactlyLikeOneThatDoesNotExist() throws Exception {
		GitHubStubs.repository(github, REPO, SHA, repository());
		String id = idOf(submit(token, REPO_URL));
		worker.runOnce();
		String otherToken = jwtService.issue(newUser());
		String unknown = UUID.randomUUID().toString();

		String othersStatus = bodyOf(getStatus(otherToken, id).andExpect(status().isNotFound()));
		String unknownStatus = bodyOf(getStatus(otherToken, unknown).andExpect(status().isNotFound()));
		String othersResult = bodyOf(getResult(otherToken, id).andExpect(status().isNotFound()));
		String unknownResult = bodyOf(getResult(otherToken, unknown).andExpect(status().isNotFound()));

		assertThat(JsonPath.<String>read(othersStatus, "$.detail")).isEqualTo(NOT_FOUND);
		assertThat(othersStatus.replace(id, "ID")).isEqualTo(unknownStatus.replace(unknown, "ID"));
		assertThat(othersResult.replace(id, "ID")).isEqualTo(unknownResult.replace(unknown, "ID"));
		// The owner still reads both.
		getStatus(token, id).andExpect(status().isOk());
		getResult(token, id).andExpect(status().isOk());
	}

	@ParameterizedTest
	@CsvSource({ "/api/reviews/not-a-uuid", "/api/reviews/123", "/api/reviews/not-a-uuid/result" })
	void idThatIsNotAUuidIsABadRequest(String path) throws Exception {
		mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	// following a review

	@Test
	void runningReviewShowsItsStageAndItsProgress() throws Exception {
		String id = idOf(submit(token, REPO_URL));
		store.claimNext(clock.instant());
		store.markFetched(UUID.fromString(id), "main", SHA);
		store.markAnalyzing(UUID.fromString(id), 5);
		store.progress(UUID.fromString(id), 2, 5);

		getStatus(token, id).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RUNNING"))
				.andExpect(jsonPath("$.stage").value("ANALYZING"))
				.andExpect(jsonPath("$.branch").value("main"))
				.andExpect(jsonPath("$.commitSha").value(SHA))
				.andExpect(jsonPath("$.chunksDone").value(2))
				.andExpect(jsonPath("$.chunksTotal").value(5))
				.andExpect(jsonPath("$.attempt").value(1))
				.andExpect(jsonPath("$.startedAt").isNotEmpty())
				.andExpect(jsonPath("$.finishedAt").value((Object) null))
				.andExpect(jsonPath("$.retryAt").value((Object) null))
				.andExpect(jsonPath("$.error").value((Object) null));
	}

	@Test
	void reportIsRefusedUntilTheReviewIsCompleted() throws Exception {
		String id = idOf(submit(token, REPO_URL));

		getResult(token, id).andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.detail").value("The review is not completed yet"));

		store.claimNext(clock.instant());
		getResult(token, id).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("The review is not completed yet"));
	}

	@Test
	void reviewIsFollowedFromSubmissionToItsReport() throws Exception {
		GitHubStubs.repository(github, REPO, SHA, repository());
		String id = idOf(submit(token, REPO_URL));
		getStatus(token, id).andExpect(jsonPath("$.status").value("QUEUED"));

		assertThat(worker.runOnce()).isTrue();

		getStatus(token, id).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.stage").value((Object) null))
				.andExpect(jsonPath("$.branch").value("main"))
				.andExpect(jsonPath("$.commitSha").value(SHA))
				.andExpect(jsonPath("$.chunksDone").value(1))
				.andExpect(jsonPath("$.chunksTotal").value(1))
				.andExpect(jsonPath("$.attempt").value(1))
				.andExpect(jsonPath("$.cacheHit").value(false))
				.andExpect(jsonPath("$.finishedAt").isNotEmpty())
				.andExpect(jsonPath("$.retryAt").value((Object) null))
				.andExpect(jsonPath("$.error").value((Object) null));

		// Four files, 8 lines, scored as 1000: critical security, high performance, medium and
		// low maintainability.
		getResult(token, id).andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.length()").value(5))
				.andExpect(jsonPath("$.scores.overall").value(81))
				.andExpect(jsonPath("$.scores.security").value(70))
				.andExpect(jsonPath("$.scores.maintainability").value(91))
				.andExpect(jsonPath("$.scores.performance").value(85))
				.andExpect(jsonPath("$.counts.critical").value(1))
				.andExpect(jsonPath("$.counts.warnings").value(2))
				.andExpect(jsonPath("$.counts.codeSmells").value(1))
				.andExpect(jsonPath("$.counts.goodPractices").value(1))
				.andExpect(jsonPath("$.stats.filesAnalyzed").value(4))
				.andExpect(jsonPath("$.stats.filesSkipped").value(1))
				.andExpect(jsonPath("$.stats.filesFailed").value(0))
				.andExpect(jsonPath("$.stats.linesAnalyzed").value(8))
				.andExpect(jsonPath("$.stats.findingsDiscarded").value(0))
				.andExpect(jsonPath("$.findings.length()").value(4))
				.andExpect(jsonPath("$.findings[*].level").value(contains("CRITICAL", "WARNING", "WARNING", "SMELL")))
				.andExpect(jsonPath("$.findings[0].id").isNumber())
				.andExpect(jsonPath("$.findings[0].category").value("SECURITY"))
				.andExpect(jsonPath("$.findings[0].severity").value("CRITICAL"))
				.andExpect(jsonPath("$.findings[0].filePath").value("src/app.py"))
				.andExpect(jsonPath("$.findings[0].lineStart").value(1))
				.andExpect(jsonPath("$.findings[0].lineEnd").value(2))
				.andExpect(jsonPath("$.findings[0].title").value("Hardcoded credential"))
				.andExpect(jsonPath("$.findings[0].explanation").isNotEmpty())
				.andExpect(jsonPath("$.findings[0].suggestion").isNotEmpty())
				.andExpect(jsonPath("$.findings[0].suggestedCode").isNotEmpty())
				.andExpect(jsonPath("$.findings[0].codeSnippet").value("import os\n\nprint(os.name)"))
				.andExpect(jsonPath("$.findings[0].snippetStartLine").value(1))
				.andExpect(jsonPath("$.findings[0].length()").value(13))
				.andExpect(jsonPath("$.findings[1].suggestedCode").value((Object) null))
				.andExpect(jsonPath("$.goodPractices.length()").value(1))
				.andExpect(jsonPath("$.goodPractices[0].level").value("GOOD_PRACTICE"))
				.andExpect(jsonPath("$.goodPractices[0].severity").value((Object) null))
				.andExpect(jsonPath("$.goodPractices[0].filePath").value((Object) null))
				.andExpect(jsonPath("$.goodPractices[0].title").value("Consistent project structure"));
	}

	@Test
	void secondReviewOfACommitGivesTheSameReportAndSaysItCameFromTheCache() throws Exception {
		GitHubStubs.repository(github, REPO, SHA, repository());
		String first = idOf(submit(token, REPO_URL));
		String otherToken = jwtService.issue(newUser());
		String second = idOf(submit(otherToken, REPO_URL));
		worker.runOnce();
		worker.runOnce();

		getStatus(otherToken, second).andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.cacheHit").value(true))
				.andExpect(jsonPath("$.commitSha").value(SHA));
		assertThat(bodyOf(getResult(otherToken, second).andExpect(status().isOk())))
				.isEqualTo(bodyOf(getResult(token, first).andExpect(status().isOk())));
		assertThat(provider.received()).hasSize(1);
	}

	@Test
	void partialReportSaysHowManyFilesCouldNotBeReviewed() throws Exception {
		String large = "x = 1\n".repeat(15_000);
		GitHubStubs.repository(github, REPO, SHA,
				TestZip.repository().file("a.py", large).file("b.py", large).file("c.py", large).bytes());
		provider.failOnCall(2, new ProviderException(ProviderErrorCode.PROVIDER_BLOCKED, "refused"));
		String id = idOf(submit(token, REPO_URL));

		worker.runOnce();

		getStatus(token, id).andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.chunksDone").value(3))
				.andExpect(jsonPath("$.chunksTotal").value(3));
		getResult(token, id).andExpect(status().isOk())
				.andExpect(jsonPath("$.stats.filesAnalyzed").value(2))
				.andExpect(jsonPath("$.stats.filesFailed").value(1))
				.andExpect(jsonPath("$.stats.linesAnalyzed").value(30_000))
				.andExpect(jsonPath("$.findings.length()").value(2))
				.andExpect(jsonPath("$.goodPractices.length()").value(1));
	}

	// failures and retries

	@Test
	void failedReviewShowsWhyAndHasNoReport() throws Exception {
		String id = idOf(submit(token, REPO_URL));

		worker.runOnce();

		getStatus(token, id).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.stage").value((Object) null))
				.andExpect(jsonPath("$.error.code").value("REPO_NOT_FOUND"))
				.andExpect(jsonPath("$.error.message").value("Repository not found or private"))
				.andExpect(jsonPath("$.error.length()").value(2))
				.andExpect(jsonPath("$.attempt").value(1))
				.andExpect(jsonPath("$.finishedAt").isNotEmpty())
				.andExpect(jsonPath("$.retryAt").value((Object) null));
		getResult(token, id).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("The review failed and has no report"));
	}

	@Test
	void reviewThatWaitsForAnotherAttemptShowsWhenItIsTriedAgain() throws Exception {
		github.on("/repos/octo/demo", Response.status(503));
		String id = idOf(submit(token, REPO_URL));

		worker.runOnce();

		String body = bodyOf(getStatus(token, id).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.attempt").value(1))
				.andExpect(jsonPath("$.maxAttempts").value(3))
				.andExpect(jsonPath("$.error").value((Object) null))
				.andExpect(jsonPath("$.retryAt").isNotEmpty()));
		assertThat(Instant.parse(JsonPath.<String>read(body, "$.retryAt")))
				.isCloseTo(clock.instant().plus(Duration.ofMinutes(1)), within(5, ChronoUnit.SECONDS));
	}

	@Test
	void retryTimeIsShownOnlyWhileItLiesAhead() throws Exception {
		github.on("/repos/octo/demo", Response.status(503));
		String id = idOf(submit(token, REPO_URL));
		worker.runOnce();
		getStatus(token, id).andExpect(jsonPath("$.retryAt").isNotEmpty());

		// The time has come and the review waits for the worker, not for a time.
		clock.advance(Duration.ofMinutes(1));

		getStatus(token, id).andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.attempt").value(1))
				.andExpect(jsonPath("$.retryAt").value((Object) null));
	}

	@Test
	void reviewPutBackAfterARestartHasNoRetryTime() throws Exception {
		String id = idOf(submit(token, REPO_URL));
		store.claimNext(clock.instant());

		assertThat(worker.recover()).isEqualTo(1);

		getStatus(token, id).andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.attempt").value(1))
				.andExpect(jsonPath("$.retryAt").value((Object) null));
	}

	// helpers

	private static byte[] repository() {
		return TestZip.repository()
				.file("README.md", "# Demo\n")
				.file("src/app.py", "import os\n\nprint(os.name)\n")
				.file("src/util.py", "def add(a, b):\n    return a + b\n")
				.file("Main.java", "class Main {\n}\n")
				.file("web/helper.js", "export const x = 1;\n")
				.bytes();
	}

	private long newUser() {
		return userRepository.save(new User("review-api-" + UUID.randomUUID() + "@example.com", "hash")).getId();
	}

	private ResultActions submit(String accessToken, String repoUrl) throws Exception {
		return mockMvc.perform(post("/api/reviews").header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"repoUrl\": \"" + repoUrl + "\"}"));
	}

	private ResultActions getStatus(String accessToken, String id) throws Exception {
		return mockMvc.perform(get("/api/reviews/" + id).header("Authorization", "Bearer " + accessToken));
	}

	private ResultActions getResult(String accessToken, String id) throws Exception {
		return mockMvc.perform(get("/api/reviews/" + id + "/result").header("Authorization", "Bearer " + accessToken));
	}

	private static String idOf(ResultActions submitted) throws Exception {
		return JsonPath.read(bodyOf(submitted.andExpect(status().isAccepted())), "$.id");
	}

	private static String bodyOf(ResultActions response) throws Exception {
		return response.andReturn().getResponse().getContentAsString();
	}

	private int count(String table) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

}
