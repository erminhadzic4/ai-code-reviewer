package com.codereviewer.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.codereviewer.github.FakeGitHub.Request;
import com.codereviewer.github.FakeGitHub.Response;
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

@ExtendWith(OutputCaptureExtension.class)
class GitHubClientTest {

	private static final RepoRef REPO = new RepoRef("octo", "demo");
	private static final String REPO_PATH = "/repos/octo/demo";
	private static final String COMMIT_PATH = "/repos/octo/demo/commits/heads/main";
	private static final String SHA = "0123abc0123abc0123abc0123abc0123abc0123a";
	private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");

	private final FakeGitHub github = new FakeGitHub();
	private final TestGitHubProperties settings = new TestGitHubProperties();

	GitHubClientTest() {
		settings.apiUrl = github.origin();
		settings.archiveUrl = github.origin();
		github.on(REPO_PATH, repository("octo/demo", "main", 120, false)).on(COMMIT_PATH, Response.text(200, SHA));
	}

	@AfterEach
	void stopServer() {
		github.close();
	}

	@Test
	void resolvesTheDefaultBranchAndItsLatestCommit() {
		RepoHead head = client().resolveHead(REPO);

		assertThat(head).isEqualTo(new RepoHead(REPO, "main", SHA));
		assertThat(github.requestedPaths()).containsExactly(REPO_PATH, COMMIT_PATH);
	}

	@Test
	void sendsTheHeadersGitHubExpects() {
		client().resolveHead(REPO);

		Request repository = github.requests().get(0);
		Request commit = github.requests().get(1);
		assertThat(repository.header("Accept")).isEqualTo("application/vnd.github+json");
		assertThat(commit.header("Accept")).isEqualTo("application/vnd.github.sha");
		assertThat(github.requests()).allSatisfy(request -> {
			assertThat(request.header("X-GitHub-Api-Version")).isEqualTo("2022-11-28");
			assertThat(request.header("User-Agent")).isEqualTo("ai-code-reviewer");
		});
	}

	@Test
	void withoutATokenNoAuthorizationIsSent() {
		client().resolveHead(REPO);

		assertThat(github.requests()).hasSize(2).allSatisfy(request -> assertThat(request.header("Authorization")).isNull());
	}

	@Test
	void theTokenIsSentAsABearerToken() {
		settings.token = "ghp_example";

		client().resolveHead(REPO);

		assertThat(github.requests()).hasSize(2)
				.allSatisfy(request -> assertThat(request.header("Authorization")).isEqualTo("Bearer ghp_example"));
	}

	@Test
	void aNewRepositoryThatReportsSizeZeroIsAccepted() {
		github.on(REPO_PATH, repository("octo/demo", "main", 0, false));

		assertThat(client().resolveHead(REPO).commitSha()).isEqualTo(SHA);
	}

	@Test
	void theNameGitHubReportsIsUsedInLowerCase() {
		github.on(REPO_PATH, repository("Octo/Demo", "main", 1, false));

		assertThat(client().resolveHead(REPO).repo()).isEqualTo(REPO);
	}

	@Test
	void aRenamedRepositoryIsFollowedToItsNewName() {
		github.on(REPO_PATH, Response.redirect(301, "/repositories/42"))
				.on("/repositories/42", repository("octo/renamed", "main", 1, false))
				.on("/repos/octo/renamed/commits/heads/main", Response.text(200, SHA));

		RepoHead head = client().resolveHead(REPO);

		assertThat(head.repo()).isEqualTo(new RepoRef("octo", "renamed"));
		assertThat(github.requestedPaths()).containsExactly(REPO_PATH, "/repositories/42",
				"/repos/octo/renamed/commits/heads/main");
	}

	// The branch name comes from GitHub but is still text someone chose.
	@ParameterizedTest
	@CsvSource({
			"release/1.0,          /repos/octo/demo/commits/heads/release/1.0",
			"feature/a/b/c,        /repos/octo/demo/commits/heads/feature/a/b/c",
			"fix/#42,              /repos/octo/demo/commits/heads/fix/%2342",
			"what?now,             /repos/octo/demo/commits/heads/what%3Fnow",
			"100%/done,            /repos/octo/demo/commits/heads/100%25/done",
			"a+b,                  /repos/octo/demo/commits/heads/a+b",
			"grün,            /repos/octo/demo/commits/heads/gr%C3%BCn" })
	void branchNameIsEncodedSegmentBySegment(String branch, String expectedPath) {
		github.on(REPO_PATH, repository("octo/demo", branch, 1, false)).on(expectedPath, Response.text(200, SHA));

		RepoHead head = client().resolveHead(REPO);

		assertThat(head.defaultBranch()).isEqualTo(branch);
		Request commit = github.requests().get(1);
		assertThat(commit.rawPath()).isEqualTo(expectedPath);
		assertThat(commit.rawQuery()).isNull();
	}

	@Test
	void missingRepositoryIsNotFound() {
		github.on(REPO_PATH, Response.json(404, "{\"message\":\"Not Found\"}"));

		assertFailsWith(GitHubErrorCode.REPO_NOT_FOUND, () -> client().resolveHead(REPO));
	}

	@Test
	void privateRepositoryIsTreatedAsNotFoundAndNothingElseIsRequested() {
		settings.token = "ghp_example";
		github.on(REPO_PATH, repository("octo/demo", "main", 1, true));

		assertFailsWith(GitHubErrorCode.REPO_NOT_FOUND, () -> client().resolveHead(REPO));
		assertThat(github.requestedPaths()).containsExactly(REPO_PATH);
	}

	@Test
	void repositoryWithoutCommitsIsEmpty() {
		github.on(COMMIT_PATH, Response.json(409, "{\"message\":\"Git Repository is empty.\"}"));

		assertFailsWith(GitHubErrorCode.REPO_EMPTY, () -> client().resolveHead(REPO));
	}

	@Test
	void repositoryAboveTheReportedSizeLimitIsRejectedBeforeAnythingElse() {
		settings.maxRepoSize = DataSize.ofMegabytes(1);
		github.on(REPO_PATH, repository("octo/demo", "main", 1025, false));

		assertFailsWith(GitHubErrorCode.REPO_TOO_LARGE, () -> client().resolveHead(REPO));
		assertThat(github.requestedPaths()).containsExactly(REPO_PATH);
	}

	@Test
	void repositoryAtTheReportedSizeLimitIsAccepted() {
		settings.maxRepoSize = DataSize.ofMegabytes(1);
		github.on(REPO_PATH, repository("octo/demo", "main", 1024, false));

		assertThat(client().resolveHead(REPO).commitSha()).isEqualTo(SHA);
	}

	@ParameterizedTest
	@ValueSource(ints = { 403, 429 })
	void primaryRateLimitGivesTheResetTime(int status) {
		Instant reset = Instant.parse("2026-10-11T10:42:00Z");
		github.on(REPO_PATH, Response.json(status, "{\"message\":\"API rate limit exceeded\"}")
				.header("x-ratelimit-remaining", "0")
				.header("x-ratelimit-reset", String.valueOf(reset.getEpochSecond())));

		assertThatThrownBy(() -> client().resolveHead(REPO)).isInstanceOfSatisfying(GitHubException.class, ex -> {
			assertThat(ex.getCode()).isEqualTo(GitHubErrorCode.RATE_LIMITED);
			assertThat(ex.isRetryable()).isTrue();
			assertThat(ex.getRetryAt()).contains(reset);
		});
	}

	@ParameterizedTest
	@ValueSource(ints = { 403, 429 })
	void secondaryRateLimitGivesTheRetryAfterTime(int status) {
		github.on(COMMIT_PATH, Response.json(status, "{\"message\":\"You have exceeded a secondary rate limit\"}")
				.header("retry-after", "60"));

		assertThatThrownBy(() -> client().resolveHead(REPO)).isInstanceOfSatisfying(GitHubException.class, ex -> {
			assertThat(ex.getCode()).isEqualTo(GitHubErrorCode.RATE_LIMITED);
			assertThat(ex.getRetryAt()).contains(NOW.plusSeconds(60));
		});
	}

	@Test
	void tooManyRequestsWithoutATimeIsStillARateLimit() {
		github.on(REPO_PATH, Response.status(429));

		assertThatThrownBy(() -> client().resolveHead(REPO)).isInstanceOfSatisfying(GitHubException.class, ex -> {
			assertThat(ex.getCode()).isEqualTo(GitHubErrorCode.RATE_LIMITED);
			assertThat(ex.getRetryAt()).isEmpty();
		});
	}

	@Test
	void rateLimitWithAnUnreadableResetTimeHasNoRetryTime() {
		github.on(REPO_PATH, Response.status(403).header("x-ratelimit-remaining", "0").header("x-ratelimit-reset", "soon"));

		assertThatThrownBy(() -> client().resolveHead(REPO)).isInstanceOfSatisfying(GitHubException.class, ex -> {
			assertThat(ex.getCode()).isEqualTo(GitHubErrorCode.RATE_LIMITED);
			assertThat(ex.getRetryAt()).isEmpty();
		});
	}

	@Test
	void forbiddenWithQuotaLeftIsARefusalNotARateLimit() {
		github.on(REPO_PATH, Response.json(403, "{\"message\":\"Repository access blocked\"}")
				.header("x-ratelimit-remaining", "37"));

		assertThatThrownBy(() -> client().resolveHead(REPO)).isInstanceOfSatisfying(GitHubException.class, ex -> {
			assertThat(ex.getCode()).isEqualTo(GitHubErrorCode.REPO_UNAVAILABLE);
			assertThat(ex.isRetryable()).isFalse();
		});
	}

	@Test
	void repositoryBlockedForLegalReasonsIsUnavailable() {
		github.on(REPO_PATH, Response.status(451));

		assertFailsWith(GitHubErrorCode.REPO_UNAVAILABLE, () -> client().resolveHead(REPO));
	}

	@Test
	void rejectedTokenIsReportedWithoutPrintingIt(CapturedOutput output) {
		settings.token = "ghp_secret-value";
		github.on(REPO_PATH, Response.json(401, "{\"message\":\"Bad credentials\"}"));

		assertThatThrownBy(() -> client().resolveHead(REPO)).isInstanceOfSatisfying(GitHubException.class, ex -> {
			assertThat(ex.getCode()).isEqualTo(GitHubErrorCode.GITHUB_AUTH_FAILED);
			assertThat(ex.getMessage()).doesNotContain("ghp_secret-value");
		});
		assertThat(output).contains("GITHUB_TOKEN").doesNotContain("ghp_secret-value");
	}

	@ParameterizedTest
	@ValueSource(ints = { 500, 502, 503, 418 })
	void serverErrorsAndUnknownStatusesAreRetryable(int status) {
		github.on(REPO_PATH, Response.status(status));

		assertThatThrownBy(() -> client().resolveHead(REPO)).isInstanceOfSatisfying(GitHubException.class, ex -> {
			assertThat(ex.getCode()).isEqualTo(GitHubErrorCode.GITHUB_UNAVAILABLE);
			assertThat(ex.isRetryable()).isTrue();
		});
	}

	@Test
	void slowAnswerTimesOut() {
		settings.readTimeout = Duration.ofMillis(200);
		github.on(REPO_PATH, repository("octo/demo", "main", 1, false).after(Duration.ofSeconds(5)));

		long started = System.nanoTime();
		assertFailsWith(GitHubErrorCode.GITHUB_UNAVAILABLE, () -> client().resolveHead(REPO));
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
	}

	@Test
	void unreachableServerIsRetryable() {
		github.close();

		assertThatThrownBy(() -> client().resolveHead(REPO)).isInstanceOfSatisfying(GitHubException.class, ex -> {
			assertThat(ex.getCode()).isEqualTo(GitHubErrorCode.GITHUB_UNAVAILABLE);
			assertThat(ex.getCause()).isNotNull();
		});
	}

	@Test
	void redirectToAnotherHostIsNotFollowed() {
		try (FakeGitHub decoy = new FakeGitHub()) {
			decoy.on(REPO_PATH, repository("octo/demo", "main", 1, false));
			settings.token = "ghp_example";
			github.on(REPO_PATH, Response.redirect(302, decoy.origin() + REPO_PATH));

			assertFailsWith(GitHubErrorCode.GITHUB_UNAVAILABLE, () -> client().resolveHead(REPO));
			assertThat(decoy.requests()).isEmpty();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://169.254.169.254/latest/meta-data", "https://evil.example/repos/octo/demo",
			"//evil.example/x", "file:///etc/passwd", "http://127.0.0.1:1/x", "http://user@127.0.0.1/x", "ht tp://bad" })
	void redirectOutsideTheConfiguredOriginsIsRefused(String location) {
		github.on(REPO_PATH, Response.redirect(302, location));

		assertFailsWith(GitHubErrorCode.GITHUB_UNAVAILABLE, () -> client().resolveHead(REPO));
		assertThat(github.requestedPaths()).containsExactly(REPO_PATH);
	}

	@Test
	void redirectWithoutALocationIsRefused() {
		github.on(REPO_PATH, Response.status(302));

		assertFailsWith(GitHubErrorCode.GITHUB_UNAVAILABLE, () -> client().resolveHead(REPO));
	}

	@Test
	void redirectLoopStopsAfterAFewRequests() {
		github.on(REPO_PATH, Response.redirect(302, REPO_PATH));

		assertFailsWith(GitHubErrorCode.GITHUB_UNAVAILABLE, () -> client().resolveHead(REPO));
		assertThat(github.requests()).hasSize(4);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{not json",
			"[]",
			"{}",
			"{\"full_name\":\"octo/demo\",\"size\":1,\"private\":false}",
			"{\"full_name\":\"octo/demo\",\"default_branch\":\"\",\"size\":1,\"private\":false}",
			"{\"full_name\":\"octo/demo\",\"default_branch\":\"ma\\nin\",\"size\":1,\"private\":false}",
			"{\"default_branch\":\"main\",\"size\":1,\"private\":false}",
			"{\"full_name\":\"../../users/octo\",\"default_branch\":\"main\",\"size\":1,\"private\":false}",
			"{\"full_name\":\"octo/demo/extra\",\"default_branch\":\"main\",\"size\":1,\"private\":false}",
			"{\"full_name\":\"octo/demo?x=1\",\"default_branch\":\"main\",\"size\":1,\"private\":false}" })
	void unusableRepositoryAnswerIsRejected(String body) {
		github.on(REPO_PATH, Response.json(200, body));

		assertFailsWith(GitHubErrorCode.GITHUB_UNAVAILABLE, () -> client().resolveHead(REPO));
		assertThat(github.requestedPaths()).containsExactly(REPO_PATH);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "0123abc", "0123ABC0123ABC0123ABC0123ABC0123ABC0123A",
			"0123abc0123abc0123abc0123abc0123abc0123a0", "<html>Sign in</html>",
			"0123abc0123abc0123abc0123abc0123abc0123a\n0123abc0123abc0123abc0123abc0123abc0123a" })
	void anythingButAFullCommitShaIsRejected(String body) {
		github.on(COMMIT_PATH, Response.text(200, body));

		assertFailsWith(GitHubErrorCode.GITHUB_UNAVAILABLE, () -> client().resolveHead(REPO));
	}

	@Test
	void shaWithSurroundingWhitespaceIsAccepted() {
		github.on(COMMIT_PATH, Response.text(200, SHA + "\n"));

		assertThat(client().resolveHead(REPO).commitSha()).isEqualTo(SHA);
	}

	private GitHubClient client() {
		return new GitHubClient(settings.build(), Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static Response repository(String fullName, String defaultBranch, long sizeInKilobytes, boolean isPrivate) {
		return Response.json(200, """
				{"id": 42, "full_name": "%s", "default_branch": "%s", "size": %d, "private": %b,
				 "owner": {"login": "octo"}, "html_url": "https://github.com/%s"}
				""".formatted(fullName, defaultBranch, sizeInKilobytes, isPrivate, fullName));
	}

	private static void assertFailsWith(GitHubErrorCode code, ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOfSatisfying(GitHubException.class,
				ex -> assertThat(ex.getCode()).isEqualTo(code));
	}

}
