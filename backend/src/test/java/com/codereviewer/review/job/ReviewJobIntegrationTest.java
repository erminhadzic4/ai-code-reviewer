package com.codereviewer.review.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.codereviewer.IntegrationTest;
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
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A review as a job: from the queue to its end, with the worker deciding what a failure
 * means. The timer is off, so every round of the worker runs here, on the test's thread, and
 * time passes only when a test moves the clock.
 */
@IntegrationTest
class ReviewJobIntegrationTest {

	private static final RepoRef REPO = new RepoRef("octo", "demo");
	private static final RepoRef OTHER_REPO = new RepoRef("octo", "other");
	private static final String SHA = "0123abc0123abc0123abc0123abc0123abc0123a";
	private static final String REPO_PATH = "/repos/octo/demo";

	@Autowired
	private ReviewWorker worker;

	@Autowired
	private ReviewJobStore store;

	@Autowired
	private ReviewPipeline pipeline;

	@Autowired
	private RetryPolicy retryPolicy;

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

	@Autowired
	private ApplicationContext context;

	@BeforeEach
	void startClean() {
		deleteReviewsAndAnalyses();
		github.reset();
		provider.reset();
		sleeper.clear();
	}

	@AfterEach
	void deleteReviewsAndAnalyses() {
		// A test that interrupts its own thread must not leave the flag for the next one.
		Thread.interrupted();
		jdbcTemplate.update("DELETE FROM reviews");
		jdbcTemplate.update("DELETE FROM analyses");
	}

	// the queue

	@Test
	void queuedReviewIsRunToItsEndInOneRound() {
		GitHubStubs.repository(github, REPO, SHA, repository());
		UUID id = submit(REPO);

		assertThat(worker.runOnce()).isTrue();

		Review stored = stored(id);
		assertThat(stored.getStatus()).isEqualTo(ReviewStatus.COMPLETED);
		assertThat(stored.getAttempts()).isEqualTo(1);
		assertThat(stored.getChunksDone()).isEqualTo(1);
		assertThat(stored.getChunksTotal()).isEqualTo(1);
		assertThat(stored.getCommitSha()).isEqualTo(SHA);
		assertThat(stored.getErrorCode()).isNull();
		assertThat(count("analyses")).isEqualTo(1);
		assertThat(count("findings")).isEqualTo(3);
	}

	@Test
	void roundWithNothingQueuedDoesNothing() {
		assertThat(worker.runOnce()).isFalse();

		assertThat(github.requests()).isEmpty();
		assertThat(provider.received()).isEmpty();
	}

	@Test
	void reviewsAreRunOneByOneInTheOrderTheyWereSubmitted() {
		GitHubStubs.repository(github, REPO, SHA, repository());
		GitHubStubs.repository(github, OTHER_REPO, SHA, repository());
		UUID first = submit(REPO);
		UUID second = submit(OTHER_REPO);

		assertThat(worker.runOnce()).isTrue();
		assertThat(stored(first).getStatus()).isEqualTo(ReviewStatus.COMPLETED);
		assertThat(stored(second).getStatus()).isEqualTo(ReviewStatus.QUEUED);

		assertThat(worker.runOnce()).isTrue();
		assertThat(stored(second).getStatus()).isEqualTo(ReviewStatus.COMPLETED);
		assertThat(worker.runOnce()).isFalse();
	}

	@Test
	void secondReviewOfACommitIsServedFromTheCache() {
		GitHubStubs.repository(github, REPO, SHA, repository());
		submit(REPO);
		UUID second = submit(REPO);

		worker.runOnce();
		worker.runOnce();

		assertThat(stored(second).getStatus()).isEqualTo(ReviewStatus.COMPLETED);
		assertThat(stored(second).isCacheHit()).isTrue();
		assertThat(provider.received()).hasSize(1);
		assertThat(count("analyses")).isEqualTo(1);
	}

	// failures that end a review at once

	@Test
	void repositoryThatDoesNotExistFailsOnTheFirstAttempt() {
		UUID id = submit(REPO);

		assertThat(worker.runOnce()).isTrue();

		Review stored = stored(id);
		assertThat(stored.getStatus()).isEqualTo(ReviewStatus.FAILED);
		assertThat(stored.getStage()).isNull();
		assertThat(stored.getErrorCode()).isEqualTo("REPO_NOT_FOUND");
		assertThat(stored.getErrorMessage()).isEqualTo("Repository not found or private");
		assertThat(stored.getAttempts()).isEqualTo(1);
		assertThat(stored.getFinishedAt()).isNotNull();
		assertThat(worker.runOnce()).isFalse();
	}

	@Test
	void repositoryWithNothingToReviewFailsWithoutAnAnalysis() {
		GitHubStubs.repository(github, REPO, SHA,
				TestZip.repository().file("README.md", "# Demo\n").file("NOTES.txt", "notes\n").bytes());
		UUID id = submit(REPO);

		worker.runOnce();

		Review stored = stored(id);
		assertThat(stored.getStatus()).isEqualTo(ReviewStatus.FAILED);
		assertThat(stored.getErrorCode()).isEqualTo("NO_REVIEWABLE_FILES");
		assertThat(stored.getErrorMessage())
				.isEqualTo("Nothing to review: all 2 files in this repository were filtered out");
		assertThat(stored.getCommitSha()).isEqualTo(SHA);
		assertThat(stored.getAttempts()).isEqualTo(1);
		assertThat(count("analyses")).isZero();
		assertThat(provider.received()).isEmpty();
	}

	@Test
	void usedUpDailyQuotaFailsTheReviewAtOnce() {
		GitHubStubs.repository(github, REPO, SHA, repository());
		provider.failOnCall(1, new ProviderException(ProviderErrorCode.PROVIDER_QUOTA_EXHAUSTED,
				"The daily quota of the review model is used up. Try again tomorrow"));
		UUID id = submit(REPO);

		worker.runOnce();

		Review stored = stored(id);
		assertThat(stored.getStatus()).isEqualTo(ReviewStatus.FAILED);
		assertThat(stored.getErrorCode()).isEqualTo("PROVIDER_QUOTA_EXHAUSTED");
		assertThat(stored.getErrorMessage())
				.isEqualTo("The daily quota of the review model is used up. Try again tomorrow");
		assertThat(stored.getAttempts()).isEqualTo(1);
		assertThat(count("analyses")).isZero();
		assertThat(sleeper.slept()).isEmpty();
	}

	@Test
	void mistakeInTheApplicationFailsTheReviewWithoutShowingItsDetails() {
		GitHubStubs.repository(github, REPO, SHA, repository());
		provider.failOnCall(1, new IllegalStateException("internal detail that is nobody's business"));
		UUID id = submit(REPO);

		assertThat(worker.runOnce()).isTrue();

		Review stored = stored(id);
		assertThat(stored.getStatus()).isEqualTo(ReviewStatus.FAILED);
		assertThat(stored.getErrorCode()).isEqualTo("INTERNAL_ERROR");
		assertThat(stored.getErrorMessage()).isEqualTo(ReviewWorker.INTERNAL_ERROR_MESSAGE);
		assertThat(stored.getAttempts()).isEqualTo(1);
		assertThat(worker.runOnce()).isFalse();
	}

	// failures that are tried again

	@Test
	void reviewThatFailsForAReasonThatCanPassIsQueuedForLater() {
		github.on(REPO_PATH, Response.status(503));
		UUID id = submit(REPO);

		assertThat(worker.runOnce()).isTrue();

		Review stored = stored(id);
		assertThat(stored.getStatus()).isEqualTo(ReviewStatus.QUEUED);
		assertThat(stored.getStage()).isNull();
		assertThat(stored.getAttempts()).isEqualTo(1);
		assertThat(stored.getErrorCode()).isNull();
		assertThat(stored.getFinishedAt()).isNull();
		assertThat(stored.getRunAfter()).isCloseTo(clock.instant().plus(Duration.ofMinutes(1)),
				within(5, ChronoUnit.SECONDS));
	}

	@Test
	void queuedRetryIsNotTakenBeforeItsTimeAndSucceedsAfterIt() {
		github.on(REPO_PATH, Response.status(503));
		UUID id = submit(REPO);
		worker.runOnce();
		GitHubStubs.repository(github, REPO, SHA, repository());

		assertThat(worker.runOnce()).isFalse();
		clock.advance(Duration.ofSeconds(50));
		assertThat(worker.runOnce()).isFalse();
		assertThat(stored(id).getStatus()).isEqualTo(ReviewStatus.QUEUED);

		clock.advance(Duration.ofSeconds(10));
		assertThat(worker.runOnce()).isTrue();

		Review stored = stored(id);
		assertThat(stored.getStatus()).isEqualTo(ReviewStatus.COMPLETED);
		assertThat(stored.getAttempts()).isEqualTo(2);
		assertThat(stored.getErrorCode()).isNull();
		// No waiting inside the job: the pause was time in the queue.
		assertThat(sleeper.slept()).isEmpty();
	}

	@Test
	void anotherReviewRunsWhileOneWaitsForItsRetry() {
		github.on(REPO_PATH, Response.status(503));
		GitHubStubs.repository(github, OTHER_REPO, SHA, repository());
		UUID waiting = submit(REPO);
		worker.runOnce();
		UUID other = submit(OTHER_REPO);

		assertThat(worker.runOnce()).isTrue();

		assertThat(stored(other).getStatus()).isEqualTo(ReviewStatus.COMPLETED);
		assertThat(stored(waiting).getStatus()).isEqualTo(ReviewStatus.QUEUED);
	}

	@Test
	void thirdFailedAttemptEndsTheReviewWithTheLastError() {
		github.on(REPO_PATH, Response.status(503));
		UUID id = submit(REPO);

		worker.runOnce();
		clock.advance(Duration.ofMinutes(1));
		worker.runOnce();
		assertThat(stored(id).getStatus()).isEqualTo(ReviewStatus.QUEUED);
		assertThat(stored(id).getAttempts()).isEqualTo(2);
		assertThat(stored(id).getRunAfter()).isCloseTo(clock.instant().plus(Duration.ofMinutes(5)),
				within(5, ChronoUnit.SECONDS));

		clock.advance(Duration.ofMinutes(5));
		assertThat(worker.runOnce()).isTrue();

		Review stored = stored(id);
		assertThat(stored.getStatus()).isEqualTo(ReviewStatus.FAILED);
		assertThat(stored.getAttempts()).isEqualTo(3);
		assertThat(stored.getErrorCode()).isEqualTo("GITHUB_UNAVAILABLE");
		assertThat(stored.getErrorMessage()).isEqualTo("GitHub is not available right now");
		clock.advance(Duration.ofHours(2));
		assertThat(worker.runOnce()).isFalse();
	}

	@Test
	void rateLimitOfGitHubPostponesTheReviewUntilTheLimitResets() {
		Instant reset = clock.instant().plus(Duration.ofMinutes(20)).truncatedTo(ChronoUnit.SECONDS);
		github.on(REPO_PATH, rateLimitedUntil(reset));
		UUID id = submit(REPO);

		worker.runOnce();

		assertThat(stored(id).getStatus()).isEqualTo(ReviewStatus.QUEUED);
		assertThat(stored(id).getRunAfter()).isEqualTo(reset);
		clock.advance(Duration.ofMinutes(19));
		assertThat(worker.runOnce()).isFalse();
	}

	@Test
	void resetTimeFarInTheFutureIsBoundedToAnHour() {
		github.on(REPO_PATH, rateLimitedUntil(clock.instant().plus(Duration.ofDays(3))));
		UUID id = submit(REPO);

		worker.runOnce();

		assertThat(stored(id).getRunAfter()).isCloseTo(clock.instant().plus(Duration.ofHours(1)),
				within(5, ChronoUnit.SECONDS));
	}

	@Test
	void providerThatKeepsFailingCostsOneAttemptAndTheNextOneStartsOver() {
		GitHubStubs.repository(github, REPO, SHA, repository());
		for (int call = 1; call <= 3; call++) {
			provider.failOnCall(call, new ProviderException(ProviderErrorCode.PROVIDER_UNAVAILABLE,
					"The review model is not available right now"));
		}
		UUID id = submit(REPO);

		worker.runOnce();

		// Three sendings of the chunk inside the job, with their pauses, then back to the queue.
		assertThat(provider.received()).hasSize(3);
		assertThat(sleeper.slept()).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(20));
		assertThat(stored(id).getStatus()).isEqualTo(ReviewStatus.QUEUED);
		assertThat(stored(id).getAttempts()).isEqualTo(1);
		assertThat(count("analyses")).isZero();

		clock.advance(Duration.ofMinutes(1));
		worker.runOnce();

		assertThat(stored(id).getStatus()).isEqualTo(ReviewStatus.COMPLETED);
		assertThat(stored(id).getAttempts()).isEqualTo(2);
		assertThat(provider.received()).hasSize(4);
		// The second attempt read the repository again from the start.
		assertThat(github.requestedPaths()).filteredOn(path -> path.contains("/zipball/")).hasSize(2);
	}

	// shutdown and recovery

	@Test
	void reviewInterruptedWhileWaitingIsLeftRunningNotFailed() {
		GitHubStubs.repository(github, REPO, SHA, repository());
		provider.interruptOnCall(1, new ProviderException(ProviderErrorCode.PROVIDER_UNAVAILABLE, "unavailable"));
		UUID id = submit(REPO);

		assertThat(worker.runOnce()).isTrue();

		// The worker hands the interrupt on to whoever runs it. Cleared before the database is used.
		assertThat(Thread.interrupted()).isTrue();
		Review stored = stored(id);
		assertThat(stored.getStatus()).isEqualTo(ReviewStatus.RUNNING);
		assertThat(stored.getStage()).isEqualTo(ReviewStage.ANALYZING);
		assertThat(stored.getAttempts()).isEqualTo(1);
		assertThat(stored.getErrorCode()).isNull();
		assertThat(provider.received()).hasSize(1);
	}

	@Test
	void failureOfARequestThatWasCutOffByAShutdownIsNotCountedAgainstTheReview() {
		GitHubStubs.repository(github, REPO, SHA, repository());
		provider.interruptOnCall(1, new ProviderException(ProviderErrorCode.PROVIDER_QUOTA_EXHAUSTED, "quota"));
		UUID id = submit(REPO);

		worker.runOnce();

		assertThat(Thread.interrupted()).isTrue();
		assertThat(stored(id).getStatus()).isEqualTo(ReviewStatus.RUNNING);
		assertThat(stored(id).getErrorCode()).isNull();
	}

	@Test
	void interruptedReviewIsQueuedAgainOnTheNextStartAndThenCompletes() {
		GitHubStubs.repository(github, REPO, SHA, repository());
		provider.interruptOnCall(1, new ProviderException(ProviderErrorCode.PROVIDER_UNAVAILABLE, "unavailable"));
		UUID id = submit(REPO);
		worker.runOnce();
		assertThat(Thread.interrupted()).isTrue();
		// Still running, so no round takes it.
		assertThat(worker.runOnce()).isFalse();

		assertThat(worker.recover()).isEqualTo(1);
		assertThat(stored(id).getStatus()).isEqualTo(ReviewStatus.QUEUED);
		assertThat(worker.runOnce()).isTrue();

		assertThat(stored(id).getStatus()).isEqualTo(ReviewStatus.COMPLETED);
		assertThat(stored(id).getAttempts()).isEqualTo(2);
	}

	@Test
	void reviewInterruptedOnEveryAttemptFailsInTheEnd() {
		UUID id = submit(REPO);
		store.claimNext(clock.instant());
		jdbcTemplate.update("UPDATE reviews SET attempts = 3 WHERE id = ?", id);

		assertThat(worker.recover()).isEqualTo(1);

		assertThat(stored(id).getStatus()).isEqualTo(ReviewStatus.FAILED);
		assertThat(stored(id).getErrorCode()).isEqualTo("REVIEW_INTERRUPTED");
		assertThat(worker.runOnce()).isFalse();
	}

	@Test
	void firstRoundAfterAStartRecoversAndLaterRoundsDoNot() {
		GitHubStubs.repository(github, REPO, SHA, repository());
		// A worker of its own, as after a start: the shared one may have had its first round.
		ReviewWorker started = new ReviewWorker(store, pipeline, retryPolicy, clock);
		UUID leftRunning = submit(REPO);
		store.claimNext(clock.instant());

		started.tick();

		assertThat(stored(leftRunning).getStatus()).isEqualTo(ReviewStatus.COMPLETED);
		assertThat(stored(leftRunning).getAttempts()).isEqualTo(2);

		// A review that is running during a later round is one this worker is not done with
		// in a real run; it must not be put back into the queue.
		UUID running = submit(REPO);
		store.claimNext(clock.instant());
		started.tick();

		assertThat(stored(running).getStatus()).isEqualTo(ReviewStatus.RUNNING);
	}

	@Test
	void timerIsSwitchedOffInTests() {
		assertThat(context.getBeansOfType(ReviewWorkerSchedule.class)).isEmpty();
	}

	// helpers

	private static byte[] repository() {
		return TestZip.repository()
				.file("README.md", "# Demo\n")
				.file("src/app.py", "import os\n\nprint(os.name)\n")
				.file("src/util.py", "def add(a, b):\n    return a + b\n")
				.bytes();
	}

	// What GitHub answers when the hourly limit of the API is used up.
	private static Response rateLimitedUntil(Instant reset) {
		return Response.status(403)
				.header("x-ratelimit-remaining", "0")
				.header("x-ratelimit-reset", String.valueOf(reset.getEpochSecond()));
	}

	/** What submitting a review does: a queued row, due at once. */
	private UUID submit(RepoRef repo) {
		long userId = userRepository.save(new User("job-" + UUID.randomUUID() + "@example.com", "hash")).getId();
		return reviewRepository.save(new Review(userId, repo)).getId();
	}

	private Review stored(UUID id) {
		return reviewRepository.findById(id).orElseThrow();
	}

	private int count(String table) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

}
