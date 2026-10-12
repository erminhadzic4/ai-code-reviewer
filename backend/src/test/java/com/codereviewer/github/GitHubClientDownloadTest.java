package com.codereviewer.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;

import com.codereviewer.testsupport.FakeHttpServer;
import com.codereviewer.testsupport.FakeHttpServer.Response;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class GitHubClientDownloadTest {

	private static final String SHA = "0123abc0123abc0123abc0123abc0123abc0123a";
	private static final RepoHead HEAD = new RepoHead(new RepoRef("octo", "demo"), "main", SHA);
	private static final String ZIPBALL_PATH = "/repos/octo/demo/zipball/" + SHA;
	private static final String ARCHIVE_PATH = "/octo/demo/legacy.zip/" + SHA;
	private static final String JAVA = "class App {\n}\n";

	// Two servers, as in production: the API and the host that serves archives.
	private final FakeHttpServer api = new FakeHttpServer();
	private final FakeHttpServer codeload = new FakeHttpServer();
	private final TestGitHubProperties settings = new TestGitHubProperties();

	GitHubClientDownloadTest() {
		settings.apiUrl = api.origin();
		settings.archiveUrl = codeload.origin();
		api.on(ZIPBALL_PATH, Response.redirect(302, codeload.origin() + ARCHIVE_PATH));
		serve(TestZip.repository().file("src/App.java", JAVA).file("README.md", "# Demo").comment(SHA).bytes());
	}

	@AfterEach
	void stopServers() {
		api.close();
		codeload.close();
	}

	@Test
	void downloadsTheArchiveOfTheCommitAndKeepsTheSourceFiles() {
		RepoSnapshot snapshot = client().fetchSnapshot(HEAD);

		assertThat(snapshot.head()).isEqualTo(HEAD);
		assertThat(snapshot.files()).containsExactly(new SourceFile("src/App.java", JAVA));
		assertThat(snapshot.skipped()).isEqualTo(Map.of(SkipReason.NON_CODE, 1));
		assertThat(api.requestedPaths()).containsExactly(ZIPBALL_PATH);
		assertThat(codeload.requestedPaths()).containsExactly(ARCHIVE_PATH);
	}

	@Test
	void theTokenGoesToTheApiButNeverToTheArchiveHost() {
		settings.token = "ghp_example";

		client().fetchSnapshot(HEAD);

		assertThat(api.requests().get(0).header("Authorization")).isEqualTo("Bearer ghp_example");
		assertThat(codeload.requests().get(0).header("Authorization")).isNull();
		assertThat(codeload.requests().get(0).header("User-Agent")).isEqualTo("ai-code-reviewer");
	}

	@Test
	void redirectToAnotherHostIsNotFollowed() {
		try (FakeHttpServer decoy = new FakeHttpServer()) {
			decoy.on(ARCHIVE_PATH, Response.zip(TestZip.repository().file("Evil.java", JAVA).bytes()));
			api.on(ZIPBALL_PATH, Response.redirect(302, decoy.origin() + ARCHIVE_PATH));

			assertFailsWith(GitHubErrorCode.GITHUB_UNAVAILABLE, () -> client().fetchSnapshot(HEAD));
			assertThat(decoy.requests()).isEmpty();
			assertThat(codeload.requests()).isEmpty();
		}
	}

	@Test
	void secondRedirectIsCheckedToo() {
		try (FakeHttpServer decoy = new FakeHttpServer()) {
			codeload.on(ARCHIVE_PATH, Response.redirect(302, decoy.origin() + "/archive.zip"));

			assertFailsWith(GitHubErrorCode.GITHUB_UNAVAILABLE, () -> client().fetchSnapshot(HEAD));
			assertThat(decoy.requests()).isEmpty();
		}
	}

	@Test
	void missingRepositoryIsNotFound() {
		api.on(ZIPBALL_PATH, Response.json(404, "{\"message\":\"Not Found\"}"));

		assertFailsWith(GitHubErrorCode.REPO_NOT_FOUND, () -> client().fetchSnapshot(HEAD));
	}

	@Test
	void rateLimitOnTheArchiveRequestIsRetryable() {
		api.on(ZIPBALL_PATH, Response.status(403).header("x-ratelimit-remaining", "0").header("x-ratelimit-reset", "1791720000"));

		assertThatThrownBy(() -> client().fetchSnapshot(HEAD)).isInstanceOfSatisfying(GitHubException.class, ex -> {
			assertThat(ex.getCode()).isEqualTo(GitHubErrorCode.RATE_LIMITED);
			assertThat(ex.getRetryAt()).isPresent();
		});
	}

	@Test
	void archiveHostFailureIsRetryable() {
		codeload.on(ARCHIVE_PATH, Response.status(503));

		assertRetryable(() -> client().fetchSnapshot(HEAD));
	}

	@Test
	void downloadLargerThanTheLimitIsStopped() {
		settings.maxDownloadSize = DataSize.ofKilobytes(100);
		byte[] incompressible = new byte[400 * 1024];
		new Random(42).nextBytes(incompressible);
		serve(TestZip.repository().file("data.bin", incompressible).bytes());

		assertFailsWith(GitHubErrorCode.REPO_TOO_LARGE, () -> client().fetchSnapshot(HEAD));
	}

	@Test
	void zipBombIsStopped() {
		settings.maxUncompressedSize = DataSize.ofMegabytes(1);
		serve(TestZip.repository().file("node_modules/bomb.js", new byte[5 * 1024 * 1024]).bytes());

		assertFailsWith(GitHubErrorCode.REPO_TOO_LARGE, () -> client().fetchSnapshot(HEAD));
	}

	@Test
	void zipSlipRejectsTheArchive() {
		serve(TestZip.repository()
				.file("Good.java", JAVA)
				.entry(TestZip.ROOT + "/../../etc/passwd", "x".getBytes(StandardCharsets.UTF_8))
				.bytes());

		assertThatThrownBy(() -> client().fetchSnapshot(HEAD)).isInstanceOfSatisfying(GitHubException.class, ex -> {
			assertThat(ex.getCode()).isEqualTo(GitHubErrorCode.ARCHIVE_INVALID);
			assertThat(ex.isRetryable()).isFalse();
		});
	}

	@Test
	void answerThatIsNotAnArchiveIsRejected() {
		codeload.on(ARCHIVE_PATH, Response.text(200, "<html><body>Sign in to continue</body></html>"));

		assertFailsWith(GitHubErrorCode.ARCHIVE_INVALID, () -> client().fetchSnapshot(HEAD));
	}

	@Test
	void archiveCutShortIsRetryableAndNeverAPartialResult() {
		byte[] archive = TestZip.repository().file("A.java", JAVA).file("B.java", JAVA).bytes();
		int secondFile = indexOf(archive, (TestZip.ROOT + "/B.java").getBytes(StandardCharsets.UTF_8)) - 30;
		serve(Arrays.copyOf(archive, secondFile));

		assertRetryable(() -> client().fetchSnapshot(HEAD));
	}

	@Test
	void slowAnswerFromTheArchiveHostTimesOut() {
		settings.downloadTimeout = Duration.ofMillis(300);
		codeload.on(ARCHIVE_PATH, Response.zip(TestZip.repository().file("A.java", JAVA).bytes()).after(Duration.ofSeconds(5)));

		assertRetryableWithin(Duration.ofSeconds(3), () -> client().fetchSnapshot(HEAD));
	}

	// The three tests below pin down one behaviour of the HTTP layer that the download
	// deadline relies on: the request timeout covers the whole exchange, body included. Nothing
	// in this project enforces the deadline a second time, so if a new version of the framework
	// starts applying the timeout only up to the response headers, these tests fail.

	// The server answers at once and then stops in the middle of the body.
	@Test
	void requestTimeoutCoversABodyThatStalls() {
		settings.downloadTimeout = Duration.ofMillis(400);
		byte[] archive = largeArchive();
		codeload.on(ARCHIVE_PATH, Response.zip(archive).inChunks(archive.length / 2, Duration.ofSeconds(10)));

		assertRetryableWithin(Duration.ofSeconds(4), () -> client().fetchSnapshot(HEAD));
	}

	// The server never stops for long, so no single read is slow; only the total is.
	@Test
	void requestTimeoutCoversABodyThatTrickles() {
		settings.downloadTimeout = Duration.ofMillis(400);
		byte[] archive = largeArchive();
		codeload.on(ARCHIVE_PATH, Response.zip(archive).inChunks(archive.length / 100, Duration.ofMillis(50)));

		assertRetryableWithin(Duration.ofSeconds(4), () -> client().fetchSnapshot(HEAD));
	}

	// Archive requests use the download timeout, not the shorter one for API answers.
	@Test
	void downloadMayTakeLongerThanTheReadTimeout() {
		settings.readTimeout = Duration.ofMillis(300);
		settings.downloadTimeout = Duration.ofSeconds(20);
		byte[] archive = largeArchive();
		codeload.on(ARCHIVE_PATH, Response.zip(archive).inChunks(archive.length / 10, Duration.ofMillis(100)));

		assertThat(client().fetchSnapshot(HEAD).files()).hasSize(1);
	}

	@Test
	void headWithoutAFullShaIsRefusedBeforeAnyRequest() {
		RepoHead branchInsteadOfSha = new RepoHead(HEAD.repo(), "main", "main");

		assertThatThrownBy(() -> client().fetchSnapshot(branchInsteadOfSha)).isInstanceOf(IllegalArgumentException.class);
		assertThat(api.requests()).isEmpty();
	}

	private GitHubClient client() {
		return new GitHubClient(settings.build());
	}

	private void serve(byte[] archive) {
		codeload.on(ARCHIVE_PATH, Response.zip(archive));
	}

	/** About 300 KB on the wire, with one small source file after the bulk. */
	private static byte[] largeArchive() {
		byte[] incompressible = new byte[300 * 1024];
		new Random(1).nextBytes(incompressible);
		return TestZip.repository().file("assets/data.bin", incompressible).file("App.java", JAVA).bytes();
	}

	private static void assertFailsWith(GitHubErrorCode code, ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOfSatisfying(GitHubException.class,
				ex -> assertThat(ex.getCode()).isEqualTo(code));
	}

	private static void assertRetryable(ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOfSatisfying(GitHubException.class, ex -> {
			assertThat(ex.getCode()).isEqualTo(GitHubErrorCode.GITHUB_UNAVAILABLE);
			assertThat(ex.isRetryable()).isTrue();
		});
	}

	private static void assertRetryableWithin(Duration limit, ThrowingCallable call) {
		long started = System.nanoTime();
		assertRetryable(call);
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(limit);
	}

	private static int indexOf(byte[] source, byte[] pattern) {
		for (int i = 0; i <= source.length - pattern.length; i++) {
			if (Arrays.equals(source, i, i + pattern.length, pattern, 0, pattern.length)) {
				return i;
			}
		}
		return -1;
	}

}
