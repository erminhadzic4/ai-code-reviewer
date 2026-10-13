package com.codereviewer.review.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.codereviewer.IntegrationTest;
import com.codereviewer.github.GitHubErrorCode;
import com.codereviewer.github.GitHubException;
import com.codereviewer.github.RepoRef;
import com.codereviewer.github.TestZip;
import com.codereviewer.provider.ProviderErrorCode;
import com.codereviewer.provider.ProviderException;
import com.codereviewer.review.Review;
import com.codereviewer.review.ReviewRepository;
import com.codereviewer.review.ReviewStage;
import com.codereviewer.review.ReviewStatus;
import com.codereviewer.testsupport.FailingReviewProvider;
import com.codereviewer.testsupport.FakeHttpServer;
import com.codereviewer.testsupport.FakeHttpServer.Response;
import com.codereviewer.testsupport.GitHubStubs;
import com.codereviewer.testsupport.MutableClock;
import com.codereviewer.testsupport.RecordingSleeper;
import com.codereviewer.user.User;
import com.codereviewer.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One review from a claimed job to its stored result, through the real GitHub client, the
 * real chunker, validator and score calculator and the real database. Only the two external
 * systems are replaced. Not transactional as a whole, because the pipeline commits each step
 * by itself; the rows are removed after each test.
 */
@IntegrationTest
class ReviewPipelineIntegrationTest {

	private static final RepoRef REPO = new RepoRef("octo", "demo");
	private static final String SHA = "0123abc0123abc0123abc0123abc0123abc0123a";
	private static final String NEXT_SHA = "89abcdef0123456789abcdef0123456789abcdef";

	/** About 190 000 characters with its line numbers: a chunk of its own under the shipped budget. */
	private static final String LARGE_FILE = "x = 1\n".repeat(15_000);

	@Autowired
	private ReviewPipeline pipeline;

	@Autowired
	private ReviewJobStore store;

	@Autowired
	private ReviewRepository reviewRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private FakeHttpServer github;

	@Autowired
	private FailingReviewProvider provider;

	@Autowired
	private MutableClock clock;

	@Autowired
	private RecordingSleeper sleeper;

	@BeforeEach
	void startClean() {
		deleteReviewsAndAnalyses();
		github.reset();
		provider.reset();
		sleeper.clear();
	}

	@AfterEach
	void deleteReviewsAndAnalyses() {
		jdbcTemplate.update("DELETE FROM reviews");
		jdbcTemplate.update("DELETE FROM analyses");
	}

	// a review that completes

	@Test
	void reviewOfASmallRepositoryIsCompletedWithItsAnalysisAndFindings() throws InterruptedException {
		GitHubStubs.repository(github, REPO, SHA, smallRepository());
		Review review = claimed(REPO);

		pipeline.run(review);

		Review stored = stored(review);
		assertThat(stored.getStatus()).isEqualTo(ReviewStatus.COMPLETED);
		assertThat(stored.getStage()).isNull();
		assertThat(stored.getBranch()).isEqualTo("main");
		assertThat(stored.getCommitSha()).isEqualTo(SHA);
		assertThat(stored.getChunksDone()).isEqualTo(1);
		assertThat(stored.getChunksTotal()).isEqualTo(1);
		assertThat(stored.isCacheHit()).isFalse();
		assertThat(stored.getAttempts()).isEqualTo(1);
		assertThat(stored.getFinishedAt()).isNotNull();
		assertThat(stored.getErrorCode()).isNull();

		// Four files of 8 lines in all are scored as 1000 lines: one critical security issue,
		// one high performance issue, one medium and one low maintainability issue.
		assertThat(analysisOf(review)).containsEntry("repo_owner", "octo")
				.containsEntry("repo_name", "demo")
				.containsEntry("commit_sha", SHA)
				.containsEntry("provider", "FAKE")
				.containsEntry("model", "fake-1")
				.containsEntry("prompt_version", 1)
				.containsEntry("security_score", 70)
				.containsEntry("maintainability_score", 91)
				.containsEntry("performance_score", 85)
				.containsEntry("overall_score", 81)
				.containsEntry("files_analyzed", 4)
				.containsEntry("files_skipped", 2)
				.containsEntry("files_failed", 0)
				.containsEntry("lines_analyzed", 8)
				.containsEntry("findings_discarded", 0);
		assertThat(findingsOf(review)).extracting(row -> row.get("kind") + " " + row.get("severity") + " "
				+ row.get("file_path")).containsExactly("ISSUE CRITICAL src/app.py", "ISSUE HIGH src/util.py",
						"ISSUE MEDIUM Main.java", "ISSUE LOW web/helper.js", "GOOD_PRACTICE null null");
		assertThat(findingsOf(review).getFirst()).containsEntry("line_start", 1)
				.containsEntry("line_end", 2)
				.containsEntry("code_snippet", "import os\n\nprint(os.name)")
				.containsEntry("snippet_start_line", 1);
		assertThat(sleeper.slept()).isEmpty();
	}

	@Test
	void repositoryIsReadWithThreeRequestsAndReviewedWithOne() throws InterruptedException {
		GitHubStubs.repository(github, REPO, SHA, smallRepository());

		pipeline.run(claimed(REPO));

		assertThat(github.requestedPaths()).containsExactly("/repos/octo/demo", "/repos/octo/demo/commits/heads/main",
				"/repos/octo/demo/zipball/" + SHA);
		assertThat(github.requests()).allSatisfy(request -> assertThat(request.header("Authorization")).isNull());
		assertThat(provider.received()).hasSize(1);
		assertThat(provider.received().getFirst().files()).extracting(file -> file.path())
				.containsExactly("src/app.py", "src/util.py", "Main.java", "web/helper.js");
	}

	@Test
	void largeRepositoryIsReviewedChunkByChunkAndPraisedOnce() throws InterruptedException {
		GitHubStubs.repository(github, REPO, SHA, threeLargeFiles());
		Review review = claimed(REPO);

		pipeline.run(review);

		assertThat(provider.received()).hasSize(3);
		Review stored = stored(review);
		assertThat(stored.getStatus()).isEqualTo(ReviewStatus.COMPLETED);
		assertThat(stored.getChunksDone()).isEqualTo(3);
		assertThat(stored.getChunksTotal()).isEqualTo(3);
		// Three critical security issues in 45 000 lines: 90 points per 1000 lines are 2.
		assertThat(analysisOf(review)).containsEntry("files_analyzed", 3)
				.containsEntry("lines_analyzed", 45_000)
				.containsEntry("security_score", 98)
				.containsEntry("maintainability_score", 100)
				.containsEntry("performance_score", 100)
				.containsEntry("overall_score", 99);
		// Every chunk comes back with the same good practice; the repository has it once, where
		// the first chunk reported it.
		assertThat(findingsOf(review)).extracting(row -> row.get("kind"))
				.containsExactly("ISSUE", "GOOD_PRACTICE", "ISSUE", "ISSUE");
	}

	// the cache

	@Test
	void secondReviewOfTheSameCommitUsesTheAnalysisOfTheFirst() throws InterruptedException {
		GitHubStubs.repository(github, REPO, SHA, smallRepository());
		Review first = claimed(REPO);
		pipeline.run(first);
		Review second = claimed(REPO, newUser());

		pipeline.run(second);

		Review stored = stored(second);
		assertThat(stored.getStatus()).isEqualTo(ReviewStatus.COMPLETED);
		assertThat(stored.isCacheHit()).isTrue();
		assertThat(stored.getBranch()).isEqualTo("main");
		assertThat(stored.getCommitSha()).isEqualTo(SHA);
		assertThat(stored.getChunksTotal()).isZero();
		assertThat(stored(first).isCacheHit()).isFalse();
		assertThat(analysisOf(second).get("id")).isEqualTo(analysisOf(first).get("id"));
		assertThat(count("analyses")).isEqualTo(1);
		assertThat(count("findings")).isEqualTo(5);
		// The second review asked GitHub for the commit only: no archive, no provider.
		assertThat(provider.received()).hasSize(1);
		assertThat(github.requestedPaths()).filteredOn(path -> path.contains("/zipball/")).hasSize(1);
		assertThat(github.requestedPaths()).hasSize(5);
	}

	@Test
	void newCommitOfTheSameRepositoryIsAnalysedAgain() throws InterruptedException {
		GitHubStubs.repository(github, REPO, SHA, smallRepository());
		Review first = claimed(REPO);
		pipeline.run(first);
		GitHubStubs.repository(github, REPO, NEXT_SHA, threeLargeFiles());
		Review second = claimed(REPO);

		pipeline.run(second);

		assertThat(stored(second).isCacheHit()).isFalse();
		assertThat(stored(second).getCommitSha()).isEqualTo(NEXT_SHA);
		assertThat(analysisOf(second).get("id")).isNotEqualTo(analysisOf(first).get("id"));
		assertThat(count("analyses")).isEqualTo(2);
	}

	@Test
	void renamedRepositoryIsStoredAndFoundUnderTheNameGitHubUsesNow() throws InterruptedException {
		RepoRef oldName = new RepoRef("octo", "old-name");
		GitHubStubs.renamed(github, oldName, REPO, SHA, smallRepository());
		GitHubStubs.repository(github, REPO, SHA, smallRepository());
		Review underOldName = claimed(oldName);
		pipeline.run(underOldName);
		Review underNewName = claimed(REPO);

		pipeline.run(underNewName);

		assertThat(analysisOf(underOldName)).containsEntry("repo_owner", "octo").containsEntry("repo_name", "demo");
		assertThat(stored(underOldName).getRepoName()).isEqualTo("old-name");
		assertThat(stored(underNewName).isCacheHit()).isTrue();
		assertThat(count("analyses")).isEqualTo(1);
	}

	// nothing to review

	@Test
	void repositoryWithOnlyFilteredFilesFailsWithoutAnAnalysis() {
		GitHubStubs.repository(github, REPO, SHA, TestZip.repository()
				.file("README.md", "# Demo\n")
				.file("docs/guide.md", "Guide\n")
				.file("LICENSE", "MIT\n")
				.bytes());
		Review review = claimed(REPO);

		assertThatThrownBy(() -> pipeline.run(review)).isInstanceOfSatisfying(ReviewFailedException.class, ex -> {
			assertThat(ex.getCode()).isEqualTo(ReviewErrorCode.NO_REVIEWABLE_FILES);
			assertThat(ex).hasMessage("Nothing to review: all 3 files in this repository were filtered out");
		});

		assertThat(provider.received()).isEmpty();
		assertThat(count("analyses")).isZero();
		// Recording the failure is the caller's part; the commit that was looked at is known.
		assertThat(stored(review).getStatus()).isEqualTo(ReviewStatus.RUNNING);
		assertThat(stored(review).getCommitSha()).isEqualTo(SHA);
	}

	@Test
	void messageNamesASingleFilteredFile() {
		GitHubStubs.repository(github, REPO, SHA, TestZip.repository().file("README.md", "# Demo\n").bytes());

		assertThatThrownBy(() -> pipeline.run(claimed(REPO)))
				.hasMessage("Nothing to review: the only file in this repository was filtered out");
	}

	@Test
	void repositoryWithoutFilesFailsTheSameWay() {
		GitHubStubs.repository(github, REPO, SHA, TestZip.repository().bytes());

		assertThatThrownBy(() -> pipeline.run(claimed(REPO))).isInstanceOfSatisfying(ReviewFailedException.class,
				ex -> {
					assertThat(ex.getCode()).isEqualTo(ReviewErrorCode.NO_REVIEWABLE_FILES);
					assertThat(ex).hasMessage("Nothing to review: this repository has no files");
				});
		assertThat(count("analyses")).isZero();
	}

	// failures are thrown, not handled

	@Test
	void failureOfGitHubIsThrownBeforeAnythingIsStored() {
		// Nothing is programmed, so the server answers 404 for the repository.
		Review review = claimed(REPO);

		assertThatThrownBy(() -> pipeline.run(review)).isInstanceOfSatisfying(GitHubException.class,
				ex -> assertThat(ex.getCode()).isEqualTo(GitHubErrorCode.REPO_NOT_FOUND));

		assertThat(stored(review).getStatus()).isEqualTo(ReviewStatus.RUNNING);
		assertThat(stored(review).getStage()).isEqualTo(ReviewStage.FETCHING);
		assertThat(stored(review).getCommitSha()).isNull();
		assertThat(provider.received()).isEmpty();
		assertThat(count("analyses")).isZero();
	}

	@Test
	void failureWhileDownloadingIsThrownAfterTheCommitIsKnown() {
		GitHubStubs.repository(github, REPO, SHA, smallRepository());
		github.on(GitHubStubs.zipballPath(REPO, SHA), Response.status(503));
		Review review = claimed(REPO);

		assertThatThrownBy(() -> pipeline.run(review)).isInstanceOfSatisfying(GitHubException.class,
				ex -> assertThat(ex.getCode()).isEqualTo(GitHubErrorCode.GITHUB_UNAVAILABLE));

		assertThat(stored(review).getCommitSha()).isEqualTo(SHA);
		assertThat(stored(review).getStage()).isEqualTo(ReviewStage.FETCHING);
		assertThat(provider.received()).isEmpty();
	}

	@Test
	void quotaThatRunsOutPartWayLeavesNoPartialAnalysis() {
		GitHubStubs.repository(github, REPO, SHA, threeLargeFiles());
		provider.failOnCall(2, new ProviderException(ProviderErrorCode.PROVIDER_QUOTA_EXHAUSTED,
				"The daily quota of the review model is used up. Try again tomorrow"));
		Review review = claimed(REPO);

		assertThatThrownBy(() -> pipeline.run(review)).isInstanceOfSatisfying(ProviderException.class,
				ex -> assertThat(ex.getCode()).isEqualTo(ProviderErrorCode.PROVIDER_QUOTA_EXHAUSTED));

		assertThat(provider.received()).hasSize(2);
		assertThat(count("analyses")).isZero();
		assertThat(count("findings")).isZero();
		Review stored = stored(review);
		assertThat(stored.getStatus()).isEqualTo(ReviewStatus.RUNNING);
		assertThat(stored.getStage()).isEqualTo(ReviewStage.ANALYZING);
		assertThat(stored.getChunksDone()).isEqualTo(1);
		assertThat(stored.getChunksTotal()).isEqualTo(3);
	}

	// failures the analysis absorbs

	@Test
	void fileTheProviderRefusesMakesTheAnalysisPartialNotFailed() throws InterruptedException {
		GitHubStubs.repository(github, REPO, SHA, threeLargeFiles());
		provider.failOnCall(2, new ProviderException(ProviderErrorCode.PROVIDER_BLOCKED,
				"The review model refused to review this content"));
		Review review = claimed(REPO);

		pipeline.run(review);

		Review stored = stored(review);
		assertThat(stored.getStatus()).isEqualTo(ReviewStatus.COMPLETED);
		assertThat(stored.getChunksDone()).isEqualTo(3);
		assertThat(stored.getChunksTotal()).isEqualTo(3);
		// The scores are computed from the 30 000 lines that were reviewed, not from all 45 000.
		assertThat(analysisOf(review)).containsEntry("files_analyzed", 2)
				.containsEntry("files_failed", 1)
				.containsEntry("lines_analyzed", 30_000)
				.containsEntry("security_score", 98);
		assertThat(findingsOf(review)).extracting(row -> row.get("file_path"))
				.containsExactly("a.py", null, "c.py");
	}

	@Test
	void partialAnalysisIsServedFromTheCacheAsItIs() throws InterruptedException {
		GitHubStubs.repository(github, REPO, SHA, threeLargeFiles());
		provider.failOnCall(2, new ProviderException(ProviderErrorCode.PROVIDER_BLOCKED, "refused"));
		pipeline.run(claimed(REPO));
		Review second = claimed(REPO);

		pipeline.run(second);

		assertThat(stored(second).isCacheHit()).isTrue();
		assertThat(analysisOf(second)).containsEntry("files_failed", 1);
		assertThat(provider.received()).hasSize(3);
	}

	@Test
	void failureThatPassesIsRetriedWithoutLeavingThePipeline() throws InterruptedException {
		GitHubStubs.repository(github, REPO, SHA, smallRepository());
		provider.failOnCall(1, new ProviderException(ProviderErrorCode.PROVIDER_UNAVAILABLE, "unavailable"));
		Review review = claimed(REPO);

		pipeline.run(review);

		assertThat(stored(review).getStatus()).isEqualTo(ReviewStatus.COMPLETED);
		assertThat(provider.received()).hasSize(2);
		assertThat(sleeper.slept()).hasSize(1);
		assertThat(count("findings")).isEqualTo(5);
	}

	// helpers

	private static byte[] smallRepository() {
		return TestZip.repository()
				.file("README.md", "# Demo\n")
				.file("src/app.py", "import os\n\nprint(os.name)\n")
				.file("src/util.py", "def add(a, b):\n    return a + b\n")
				.file("Main.java", "class Main {\n}\n")
				.file("package-lock.json", "{}\n")
				.file("web/helper.js", "export const x = 1;\n")
				.bytes();
	}

	private static byte[] threeLargeFiles() {
		return TestZip.repository().file("a.py", LARGE_FILE).file("b.py", LARGE_FILE).file("c.py", LARGE_FILE).bytes();
	}

	private long newUser() {
		return userRepository.save(new User("pipeline-" + UUID.randomUUID() + "@example.com", "hash")).getId();
	}

	private Review claimed(RepoRef repo) {
		return claimed(repo, newUser());
	}

	/** A review as the worker hands it to the pipeline: taken from the queue and running. */
	private Review claimed(RepoRef repo, long userId) {
		UUID id = reviewRepository.save(new Review(userId, repo)).getId();
		Review claimed = store.claimNext(clock.instant().truncatedTo(ChronoUnit.MICROS)).orElseThrow();
		assertThat(claimed.getId()).isEqualTo(id);
		return claimed;
	}

	private Review stored(Review review) {
		return reviewRepository.findById(review.getId()).orElseThrow();
	}

	private Map<String, Object> analysisOf(Review review) {
		return jdbcTemplate.queryForMap(
				"SELECT a.* FROM analyses a JOIN reviews r ON r.analysis_id = a.id WHERE r.id = ?", review.getId());
	}

	private List<Map<String, Object>> findingsOf(Review review) {
		return jdbcTemplate.queryForList("""
				SELECT f.* FROM findings f JOIN reviews r ON r.analysis_id = f.analysis_id
				WHERE r.id = ? ORDER BY f.id""", review.getId());
	}

	private int count(String table) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

}
