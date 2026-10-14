package com.codereviewer.review.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.codereviewer.IntegrationTest;
import com.codereviewer.github.RepoRef;
import com.codereviewer.provider.CodeReviewProvider;
import com.codereviewer.provider.FakeReviewProvider;
import com.codereviewer.provider.FindingCategory;
import com.codereviewer.provider.FindingKind;
import com.codereviewer.provider.ProviderLimits;
import com.codereviewer.provider.RawFinding;
import com.codereviewer.provider.ReviewChunk;
import com.codereviewer.provider.Severity;
import com.codereviewer.provider.VerifiedFinding;
import com.codereviewer.review.Analysis;
import com.codereviewer.review.Review;
import com.codereviewer.review.ReviewRepository;
import com.codereviewer.review.ReviewStage;
import com.codereviewer.review.ReviewStatus;
import com.codereviewer.testsupport.MutableClock;
import com.codereviewer.user.User;
import com.codereviewer.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The writes of a job against the real database. Not transactional as a whole: every call
 * to the store must commit by itself, as it does in a job, and two of the tests need a second
 * transaction that runs at the same time. The rows are removed after each test instead.
 */
@IntegrationTest
class ReviewJobStoreIntegrationTest {

	private static final RepoRef REPO = new RepoRef("octocat", "hello-world");
	private static final String SHA = "89abcdef0123456789abcdef0123456789abcdef";
	private static final String OTHER_SHA = "0123456789abcdef0123456789abcdef01234567";
	private static final CodeReviewProvider PROVIDER = new FakeReviewProvider();
	private static final JobFailure UNAVAILABLE = new JobFailure("GITHUB_UNAVAILABLE", "GitHub is unavailable", true,
			null);

	@Autowired
	private ReviewJobStore store;

	@Autowired
	private ReviewRepository reviewRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private MutableClock clock;

	private long userId;

	@BeforeEach
	void createUser() {
		deleteReviewsAndAnalyses();
		userId = userRepository.save(new User("job-store-" + UUID.randomUUID() + "@example.com", "hash")).getId();
	}

	@AfterEach
	void deleteReviewsAndAnalyses() {
		jdbcTemplate.update("DELETE FROM reviews");
		jdbcTemplate.update("DELETE FROM analyses");
	}

	// claiming

	@Test
	void claimingStartsTheReviewAndStoresThat() {
		UUID id = queued();
		Instant now = now();

		Optional<Review> claimed = store.claimNext(now);

		assertThat(claimed).map(Review::getId).contains(id);
		assertThat(claimed.orElseThrow().getStatus()).isEqualTo(ReviewStatus.RUNNING);
		Review stored = stored(id);
		assertThat(stored.getStatus()).isEqualTo(ReviewStatus.RUNNING);
		assertThat(stored.getStage()).isEqualTo(ReviewStage.FETCHING);
		assertThat(stored.getAttempts()).isEqualTo(1);
		assertThat(stored.getStartedAt()).isEqualTo(now);
	}

	@Test
	void nothingIsClaimedWhenNothingIsQueued() {
		assertThat(store.claimNext(now())).isEmpty();
	}

	@Test
	void reviewThatHasWaitedLongestIsClaimedFirst() {
		Instant now = now();
		UUID second = queuedSince(now.minusSeconds(60));
		UUID first = queuedSince(now.minusSeconds(120));
		UUID third = queuedSince(now.minusSeconds(30));

		assertThat(store.claimNext(now)).map(Review::getId).contains(first);
		assertThat(store.claimNext(now)).map(Review::getId).contains(second);
		assertThat(store.claimNext(now)).map(Review::getId).contains(third);
		assertThat(store.claimNext(now)).isEmpty();
	}

	@Test
	void reviewsDueAtTheSameTimeAreClaimedInTheOrderTheyWereSubmitted() {
		Instant now = now();
		UUID later = queued();
		UUID earlier = queued();
		jdbcTemplate.update("UPDATE reviews SET run_after = ?", Timestamp.from(now.minusSeconds(10)));
		jdbcTemplate.update("UPDATE reviews SET created_at = ? WHERE id = ?", Timestamp.from(now.minusSeconds(50)),
				later);
		jdbcTemplate.update("UPDATE reviews SET created_at = ? WHERE id = ?", Timestamp.from(now.minusSeconds(90)),
				earlier);

		assertThat(store.claimNext(now)).map(Review::getId).contains(earlier);
		assertThat(store.claimNext(now)).map(Review::getId).contains(later);
	}

	@Test
	void reviewIsNotClaimedBeforeItIsDue() {
		UUID id = queued();
		store.claimNext(now());
		store.requeue(id, UNAVAILABLE, now().plus(Duration.ofMinutes(5)));

		assertThat(store.claimNext(now())).isEmpty();
		clock.advance(Duration.ofMinutes(4));
		assertThat(store.claimNext(now())).isEmpty();

		clock.advance(Duration.ofMinutes(1));
		assertThat(store.claimNext(now())).map(Review::getId).contains(id);
		assertThat(stored(id).getAttempts()).isEqualTo(2);
	}

	@Test
	void reviewThatIsDueGoesBeforeAnOlderOneThatIsNot() {
		Instant now = now();
		UUID waiting = queuedSince(now.plus(Duration.ofMinutes(5)));
		jdbcTemplate.update("UPDATE reviews SET created_at = ? WHERE id = ?", Timestamp.from(now.minusSeconds(600)),
				waiting);
		UUID due = queued();

		assertThat(store.claimNext(now())).map(Review::getId).contains(due);
		assertThat(store.claimNext(now())).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "RUNNING", "COMPLETED", "FAILED" })
	void onlyQueuedReviewsAreClaimed(String status) {
		UUID id = queued();
		if (status.equals("COMPLETED")) {
			long analysisId = insertAnalysis(SHA);
			jdbcTemplate.update("UPDATE reviews SET status = 'COMPLETED', analysis_id = ? WHERE id = ?", analysisId, id);
		}
		else {
			jdbcTemplate.update("UPDATE reviews SET status = ?, error_code = 'X' WHERE id = ?", status, id);
		}

		assertThat(store.claimNext(now())).isEmpty();
	}

	@Test
	void reviewLockedByAnotherTransactionIsSkippedNotWaitedFor() throws Exception {
		Instant now = now();
		UUID locked = queuedSince(now.minusSeconds(120));
		UUID free = queuedSince(now.minusSeconds(60));

		Optional<Review> claimed = whileAnotherTransactionHolds(locked, () -> store.claimNext(now));

		assertThat(claimed).map(Review::getId).contains(free);
		assertThat(stored(locked).getStatus()).isEqualTo(ReviewStatus.QUEUED);
	}

	@Test
	void nothingIsClaimedWhileTheOnlyDueReviewIsLocked() throws Exception {
		Instant now = now();
		UUID locked = queuedSince(now.minusSeconds(120));

		Optional<Review> claimed = whileAnotherTransactionHolds(locked, () -> store.claimNext(now));

		assertThat(claimed).isEmpty();
		// Once the other transaction is gone the review can be claimed again.
		assertThat(store.claimNext(now)).map(Review::getId).contains(locked);
	}

	// the steps of a running review

	@Test
	void stepsOfARunningReviewAreStoredOneByOne() {
		UUID id = running();

		store.markFetched(id, "main", SHA);
		assertThat(stored(id).getBranch()).isEqualTo("main");
		assertThat(stored(id).getCommitSha()).isEqualTo(SHA);
		assertThat(stored(id).getStage()).isEqualTo(ReviewStage.FETCHING);

		store.markAnalyzing(id, 3);
		assertThat(stored(id).getStage()).isEqualTo(ReviewStage.ANALYZING);
		assertThat(stored(id).getChunksDone()).isZero();
		assertThat(stored(id).getChunksTotal()).isEqualTo(3);

		store.progress(id, 2, 4);
		assertThat(stored(id).getChunksDone()).isEqualTo(2);
		assertThat(stored(id).getChunksTotal()).isEqualTo(4);

		store.markScoring(id);
		assertThat(stored(id).getStage()).isEqualTo(ReviewStage.SCORING);
		assertThat(stored(id).getStatus()).isEqualTo(ReviewStatus.RUNNING);
	}

	@Test
	void stepThatIsNotAllowedChangesNothing() {
		UUID id = queued();

		assertThatThrownBy(() -> store.progress(id, 1, 2)).isInstanceOf(IllegalStateException.class);

		assertThat(stored(id).getStatus()).isEqualTo(ReviewStatus.QUEUED);
		assertThat(stored(id).getChunksTotal()).isZero();
	}

	@Test
	void reviewThatDoesNotExistIsAMistakeOfTheCaller() {
		UUID unknown = UUID.randomUUID();

		assertThatThrownBy(() -> store.markScoring(unknown)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(unknown.toString());
	}

	// completing

	@Test
	void completingStoresTheAnalysisItsFindingsAndTheEndOfTheReview() {
		UUID id = running();
		store.markFetched(id, "main", SHA);
		Instant now = now();

		store.complete(id, analysis(SHA), List.of(issue("a.py"), goodPractice()), now);

		Review stored = stored(id);
		assertThat(stored.getStatus()).isEqualTo(ReviewStatus.COMPLETED);
		assertThat(stored.getStage()).isNull();
		assertThat(stored.isCacheHit()).isFalse();
		assertThat(stored.getFinishedAt()).isEqualTo(now);
		Map<String, Object> analysis = jdbcTemplate.queryForMap(
				"SELECT a.* FROM analyses a JOIN reviews r ON r.analysis_id = a.id WHERE r.id = ?", id);
		assertThat(analysis).containsEntry("commit_sha", SHA)
				.containsEntry("provider", "FAKE")
				.containsEntry("files_analyzed", 12)
				.containsEntry("files_failed", 2)
				.containsEntry("lines_analyzed", 1840);
		List<Map<String, Object>> findings = jdbcTemplate.queryForList(
				"SELECT kind, file_path, code_snippet, snippet_start_line FROM findings WHERE analysis_id = ? ORDER BY id",
				analysis.get("id"));
		assertThat(findings).hasSize(2);
		assertThat(findings.get(0)).containsEntry("kind", "ISSUE")
				.containsEntry("file_path", "a.py")
				.containsEntry("code_snippet", "x = 1\ny = 2")
				.containsEntry("snippet_start_line", 1);
		assertThat(findings.get(1)).containsEntry("kind", "GOOD_PRACTICE").containsEntry("code_snippet", null);
	}

	@Test
	void analysisWithoutFindingsCompletesTheReview() {
		UUID id = running();

		store.complete(id, analysis(SHA), List.of(), now());

		assertThat(stored(id).getStatus()).isEqualTo(ReviewStatus.COMPLETED);
		assertThat(count("findings")).isZero();
		assertThat(count("analyses")).isEqualTo(1);
	}

	@Test
	void nothingIsStoredWhenOneFindingCannotBe() {
		UUID id = running();
		// An issue without a severity never passes validation; the table rejects it too.
		VerifiedFinding invalid = new VerifiedFinding(new RawFinding(FindingKind.ISSUE, FindingCategory.SECURITY, null,
				"a.py", 1, 1, "Title", null, null, null), null, null);

		assertThatThrownBy(() -> store.complete(id, analysis(SHA), List.of(issue("a.py"), invalid), now()))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(count("analyses")).isZero();
		assertThat(count("findings")).isZero();
		assertThat(stored(id).getStatus()).isEqualTo(ReviewStatus.RUNNING);
	}

	@Test
	void reviewThatIsNotRunningCannotBeCompletedAndLeavesNoAnalysis() {
		UUID id = queued();

		assertThatThrownBy(() -> store.complete(id, analysis(SHA), List.of(issue("a.py")), now()))
				.isInstanceOf(IllegalStateException.class);

		assertThat(count("analyses")).isZero();
		assertThat(count("findings")).isZero();
	}

	// the cache

	@Test
	void commitThatWasNeverAnalysedIsNotInTheCache() {
		assertThat(store.findCached(REPO, SHA, PROVIDER)).isEmpty();
	}

	@Test
	void storedAnalysisIsFoundByItsCacheKey() {
		UUID id = running();
		store.complete(id, analysis(SHA), List.of(), now());

		Optional<Analysis> cached = store.findCached(REPO, SHA, PROVIDER);

		assertThat(cached).isPresent();
		assertThat(cached.orElseThrow().getCommitSha()).isEqualTo(SHA);
		assertThat(cached.orElseThrow().getOverallScore()).isEqualTo(72);
	}

	@Test
	void analysisIsNotServedForAnotherCommitRepositoryModelOrPrompt() {
		store.complete(running(), analysis(SHA), List.of(), now());

		assertThat(store.findCached(REPO, OTHER_SHA, PROVIDER)).isEmpty();
		assertThat(store.findCached(new RepoRef("octocat", "other"), SHA, PROVIDER)).isEmpty();
		assertThat(store.findCached(new RepoRef("someone", "hello-world"), SHA, PROVIDER)).isEmpty();
		assertThat(store.findCached(REPO, SHA, provider("GEMINI", "fake-1", 1))).isEmpty();
		assertThat(store.findCached(REPO, SHA, provider("FAKE", "fake-2", 1))).isEmpty();
		assertThat(store.findCached(REPO, SHA, provider("FAKE", "fake-1", 2))).isEmpty();
		assertThat(store.findCached(REPO, SHA, provider("FAKE", "fake-1", 1))).isPresent();
	}

	@Test
	void secondReviewOfACommitIsCompletedWithTheAnalysisOfTheFirst() {
		UUID first = running();
		store.complete(first, analysis(SHA), List.of(issue("a.py")), now());
		long analysisId = store.findCached(REPO, SHA, PROVIDER).orElseThrow().getId();
		UUID second = running();
		Instant now = now();

		store.completeFromCache(second, analysisId, now);

		Review stored = stored(second);
		assertThat(stored.getStatus()).isEqualTo(ReviewStatus.COMPLETED);
		assertThat(stored.isCacheHit()).isTrue();
		assertThat(stored.getFinishedAt()).isEqualTo(now);
		assertThat(analysisIdOf(second)).isEqualTo(analysisId).isEqualTo(analysisIdOf(first));
		assertThat(count("analyses")).isEqualTo(1);
		assertThat(count("findings")).isEqualTo(1);
	}

	// requeueing and failing

	@Test
	void requeuedReviewWaitsForItsNextAttempt() {
		UUID id = running();
		store.markAnalyzing(id, 3);
		store.progress(id, 2, 3);
		Instant retryAt = now().plus(Duration.ofMinutes(1));

		store.requeue(id, UNAVAILABLE, retryAt);

		Review stored = stored(id);
		assertThat(stored.getStatus()).isEqualTo(ReviewStatus.QUEUED);
		assertThat(stored.getStage()).isNull();
		assertThat(stored.getRunAfter()).isEqualTo(retryAt);
		assertThat(stored.getChunksDone()).isZero();
		assertThat(stored.getChunksTotal()).isZero();
		assertThat(stored.getAttempts()).isEqualTo(1);
		assertThat(stored.getErrorCode()).isEqualTo("GITHUB_UNAVAILABLE");
		assertThat(stored.getErrorMessage()).isEqualTo("GitHub is unavailable");
	}

	@Test
	void failedReviewKeepsTheCodeAndTheMessageOfItsFailure() {
		UUID id = running();
		Instant now = now();

		store.fail(id, JobFailure.of(ReviewErrorCode.NO_REVIEWABLE_FILES, "Nothing to review"), now);

		Review stored = stored(id);
		assertThat(stored.getStatus()).isEqualTo(ReviewStatus.FAILED);
		assertThat(stored.getStage()).isNull();
		assertThat(stored.getErrorCode()).isEqualTo("NO_REVIEWABLE_FILES");
		assertThat(stored.getErrorMessage()).isEqualTo("Nothing to review");
		assertThat(stored.getFinishedAt()).isEqualTo(now);
		assertThat(store.claimNext(now())).isEmpty();
	}

	// recovery

	@Test
	void reviewLeftRunningIsQueuedAgainAndDueAtOnce() {
		UUID id = running();
		store.markAnalyzing(id, 3);
		store.progress(id, 2, 3);
		Instant now = now();

		int recovered = store.recover(now);

		assertThat(recovered).isEqualTo(1);
		Review stored = stored(id);
		assertThat(stored.getStatus()).isEqualTo(ReviewStatus.QUEUED);
		assertThat(stored.getStage()).isNull();
		assertThat(stored.getChunksDone()).isZero();
		assertThat(stored.getChunksTotal()).isZero();
		assertThat(stored.getRunAfter()).isEqualTo(now);
		assertThat(stored.getAttempts()).isEqualTo(1);
		assertThat(store.claimNext(now)).map(Review::getId).contains(id);
		assertThat(stored(id).getAttempts()).isEqualTo(2);
	}

	@Test
	void reviewLeftRunningOnItsLastAttemptFails() {
		UUID id = running();
		jdbcTemplate.update("UPDATE reviews SET attempts = 3 WHERE id = ?", id);
		Instant now = now();

		int recovered = store.recover(now);

		assertThat(recovered).isEqualTo(1);
		Review stored = stored(id);
		assertThat(stored.getStatus()).isEqualTo(ReviewStatus.FAILED);
		assertThat(stored.getErrorCode()).isEqualTo("REVIEW_INTERRUPTED");
		assertThat(stored.getErrorMessage()).isEqualTo(ReviewJobStore.INTERRUPTED_MESSAGE);
		assertThat(stored.getFinishedAt()).isEqualTo(now);
		assertThat(store.claimNext(now)).isEmpty();
	}

	@Test
	void reviewOnItsSecondAttemptStillGetsItsThird() {
		UUID id = running();
		jdbcTemplate.update("UPDATE reviews SET attempts = 2 WHERE id = ?", id);

		store.recover(now());

		assertThat(stored(id).getStatus()).isEqualTo(ReviewStatus.QUEUED);
	}

	@Test
	void recoveryLeavesEveryOtherReviewAlone() {
		UUID waiting = queued();
		Instant retryAt = now().plus(Duration.ofMinutes(5));
		store.claimNext(now());
		store.requeue(waiting, UNAVAILABLE, retryAt);
		UUID completed = running();
		store.complete(completed, analysis(SHA), List.of(), now());
		UUID failed = running();
		store.fail(failed, JobFailure.of(ReviewErrorCode.INTERNAL_ERROR, "message"), now());

		int recovered = store.recover(now());

		assertThat(recovered).isZero();
		assertThat(stored(waiting).getStatus()).isEqualTo(ReviewStatus.QUEUED);
		assertThat(stored(waiting).getRunAfter()).isEqualTo(retryAt);
		assertThat(stored(completed).getStatus()).isEqualTo(ReviewStatus.COMPLETED);
		assertThat(stored(failed).getStatus()).isEqualTo(ReviewStatus.FAILED);
		assertThat(stored(failed).getErrorCode()).isEqualTo("INTERNAL_ERROR");
	}

	@Test
	void everyReviewLeftRunningIsRecovered() {
		UUID first = running();
		UUID second = running();
		jdbcTemplate.update("UPDATE reviews SET attempts = 3 WHERE id = ?", second);

		assertThat(store.recover(now())).isEqualTo(2);

		assertThat(stored(first).getStatus()).isEqualTo(ReviewStatus.QUEUED);
		assertThat(stored(second).getStatus()).isEqualTo(ReviewStatus.FAILED);
	}

	// helpers

	// The database keeps microseconds; a finer time would not compare equal after it is read back.
	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

	private UUID queued() {
		return reviewRepository.save(new Review(userId, REPO)).getId();
	}

	/** A queued review whose turn comes at the given time. */
	private UUID queuedSince(Instant runAfter) {
		UUID id = queued();
		jdbcTemplate.update("UPDATE reviews SET run_after = ? WHERE id = ?", Timestamp.from(runAfter), id);
		return id;
	}

	/** A review that a worker has claimed. Nothing else is queued when this is called. */
	private UUID running() {
		UUID id = queued();
		assertThat(store.claimNext(now())).map(Review::getId).contains(id);
		return id;
	}

	private Review stored(UUID id) {
		return reviewRepository.findById(id).orElseThrow();
	}

	private Long analysisIdOf(UUID reviewId) {
		return jdbcTemplate.queryForObject("SELECT analysis_id FROM reviews WHERE id = ?", Long.class, reviewId);
	}

	private int count(String table) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

	private long insertAnalysis(String sha) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO analyses (repo_owner, repo_name, commit_sha, provider, model, prompt_version,
						overall_score, security_score, maintainability_score, performance_score,
						files_analyzed, files_skipped, lines_analyzed, findings_discarded)
				VALUES ('octocat', 'hello-world', ?, 'FAKE', 'fake-1', 1, 80, 80, 80, 80, 1, 0, 10, 0)
				RETURNING id""", Long.class, sha);
	}

	private static Analysis analysis(String sha) {
		return new Analysis(REPO, sha, PROVIDER.name(), PROVIDER.model(), PROVIDER.promptVersion(), 72, 0, 100, 85, 12,
				3, 2, 1840, 1);
	}

	private static VerifiedFinding issue(String path) {
		return new VerifiedFinding(new RawFinding(FindingKind.ISSUE, FindingCategory.SECURITY, Severity.HIGH, path, 1,
				1, "Title", "Explanation", "Suggestion", null), "x = 1\ny = 2", 1);
	}

	private static VerifiedFinding goodPractice() {
		return new VerifiedFinding(new RawFinding(FindingKind.GOOD_PRACTICE, FindingCategory.MAINTAINABILITY, null,
				null, null, null, "Consistent structure", null, null, null), null, null);
	}

	private static CodeReviewProvider provider(String name, String model, int promptVersion) {
		return new CodeReviewProvider() {

			@Override
			public String name() {
				return name;
			}

			@Override
			public String model() {
				return model;
			}

			@Override
			public int promptVersion() {
				return promptVersion;
			}

			@Override
			public ProviderLimits limits() {
				return ProviderLimits.UNLIMITED;
			}

			@Override
			public List<RawFinding> review(ReviewChunk chunk) {
				throw new UnsupportedOperationException();
			}

		};
	}

	/**
	 * Runs {@code action} while a second transaction holds a row lock on the given review, as
	 * another worker would between claiming it and committing. If the action waited for that
	 * lock, it would return only after the holder gives up, and with a different result.
	 */
	private <T> T whileAnotherTransactionHolds(UUID reviewId, Supplier<T> action) throws Exception {
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
				jdbcTemplate.queryForList("SELECT id FROM reviews WHERE id = ? FOR UPDATE", reviewId);
				locked.countDown();
				try {
					release.await(10, TimeUnit.SECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}));
			assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
			long start = System.nanoTime();
			T result = action.get();
			assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
			release.countDown();
			holder.get(10, TimeUnit.SECONDS);
			return result;
		}
		finally {
			release.countDown();
			executor.shutdownNow();
		}
	}

}
