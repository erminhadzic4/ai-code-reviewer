package com.codereviewer.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.codereviewer.github.RepoRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ReviewTest {

	private static final RepoRef REPO = new RepoRef("octocat", "hello-world");
	private static final String SHA = "89abcdef0123456789abcdef0123456789abcdef";
	private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");

	@Test
	void startingTakesTheReviewOutOfTheQueue() {
		Review review = new Review(1, REPO);

		review.start(NOW);

		assertThat(review.getStatus()).isEqualTo(ReviewStatus.RUNNING);
		assertThat(review.getStage()).isEqualTo(ReviewStage.FETCHING);
		assertThat(review.getAttempts()).isEqualTo(1);
		assertThat(review.getStartedAt()).isEqualTo(NOW);
		assertThat(review.getFinishedAt()).isNull();
	}

	@Test
	void runningReviewGoesThroughItsStages() {
		Review review = running();

		review.fetched("main", SHA);
		assertThat(review.getStage()).isEqualTo(ReviewStage.FETCHING);
		assertThat(review.getBranch()).isEqualTo("main");
		assertThat(review.getCommitSha()).isEqualTo(SHA);

		review.analyzing(3);
		assertThat(review.getStage()).isEqualTo(ReviewStage.ANALYZING);
		assertThat(review.getChunksDone()).isZero();
		assertThat(review.getChunksTotal()).isEqualTo(3);

		review.progress(2, 3);
		assertThat(review.getChunksDone()).isEqualTo(2);

		review.scoring();
		assertThat(review.getStage()).isEqualTo(ReviewStage.SCORING);
		assertThat(review.getStatus()).isEqualTo(ReviewStatus.RUNNING);
	}

	@Test
	void totalGrowsWhenAChunkIsSplit() {
		Review review = running();
		review.analyzing(3);

		review.progress(1, 4);

		assertThat(review.getChunksDone()).isEqualTo(1);
		assertThat(review.getChunksTotal()).isEqualTo(4);
	}

	@Test
	void completingLinksTheAnalysisAndEndsTheJob() {
		Review review = running();
		review.analyzing(2);
		review.progress(2, 2);
		review.scoring();
		Analysis analysis = analysis();
		Instant later = NOW.plusSeconds(30);

		review.complete(analysis, false, later);

		assertThat(review.getStatus()).isEqualTo(ReviewStatus.COMPLETED);
		assertThat(review.getStage()).isNull();
		assertThat(review.getAnalysis()).isSameAs(analysis);
		assertThat(review.isCacheHit()).isFalse();
		assertThat(review.getFinishedAt()).isEqualTo(later);
		assertThat(review.getChunksDone()).isEqualTo(2);
		assertThat(review.getChunksTotal()).isEqualTo(2);
	}

	@Test
	void reviewServedFromTheCacheCompletesWithoutAnalysing() {
		Review review = running();
		review.fetched("main", SHA);

		review.complete(analysis(), true, NOW);

		assertThat(review.getStatus()).isEqualTo(ReviewStatus.COMPLETED);
		assertThat(review.isCacheHit()).isTrue();
		assertThat(review.getChunksTotal()).isZero();
	}

	@Test
	void completingNeedsAnAnalysis() {
		Review review = running();

		assertThatThrownBy(() -> review.complete(null, false, NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThat(review.getStatus()).isEqualTo(ReviewStatus.RUNNING);
	}

	@Test
	void requeueingReturnsTheReviewToTheQueueWithoutProgress() {
		Review review = running();
		review.analyzing(4);
		review.progress(3, 4);
		Instant retryAt = NOW.plus(Duration.ofMinutes(1));

		review.requeue(retryAt);

		assertThat(review.getStatus()).isEqualTo(ReviewStatus.QUEUED);
		assertThat(review.getStage()).isNull();
		assertThat(review.getChunksDone()).isZero();
		assertThat(review.getChunksTotal()).isZero();
		assertThat(review.getRunAfter()).isEqualTo(retryAt);
		assertThat(review.getAttempts()).isEqualTo(1);
	}

	@Test
	void requeuedReviewKeepsTheReasonItsAttemptFailed() {
		Review review = running();

		review.requeue(NOW.plusSeconds(60), "RATE_LIMITED", "GitHub rate limit reached");

		assertThat(review.getErrorCode()).isEqualTo("RATE_LIMITED");
		assertThat(review.getErrorMessage()).isEqualTo("GitHub rate limit reached");
	}

	@Test
	void reviewRequeuedWithoutAFailureHasNoError() {
		Review review = running();
		review.requeue(NOW, "RATE_LIMITED", "GitHub rate limit reached");
		review.start(NOW);

		review.requeue(NOW);

		assertThat(review.getErrorCode()).isNull();
		assertThat(review.getErrorMessage()).isNull();
	}

	@Test
	void nextStartClearsTheErrorOfTheAttemptBefore() {
		Review review = running();
		review.requeue(NOW, "RATE_LIMITED", "GitHub rate limit reached");

		review.start(NOW.plusSeconds(60));

		assertThat(review.getErrorCode()).isNull();
		assertThat(review.getErrorMessage()).isNull();
	}

	@Test
	void longMessageOfARequeuedReviewIsCutToTheColumn() {
		Review review = running();

		review.requeue(NOW, "RATE_LIMITED", "x".repeat(Review.MAX_ERROR_MESSAGE_LENGTH + 10));

		assertThat(review.getErrorMessage()).hasSize(Review.MAX_ERROR_MESSAGE_LENGTH);
	}

	@Test
	void everyStartIsAnotherAttempt() {
		Review review = running();
		review.requeue(NOW);
		Instant second = NOW.plusSeconds(60);

		review.start(second);

		assertThat(review.getAttempts()).isEqualTo(2);
		assertThat(review.getStage()).isEqualTo(ReviewStage.FETCHING);
		assertThat(review.getStartedAt()).isEqualTo(second);
	}

	@Test
	void failingKeepsTheErrorAndEndsTheJob() {
		Review review = running();
		review.analyzing(4);
		review.progress(1, 4);
		Instant later = NOW.plusSeconds(5);

		review.fail("REPO_NOT_FOUND", "Repository not found or private", later);

		assertThat(review.getStatus()).isEqualTo(ReviewStatus.FAILED);
		assertThat(review.getStage()).isNull();
		assertThat(review.getErrorCode()).isEqualTo("REPO_NOT_FOUND");
		assertThat(review.getErrorMessage()).isEqualTo("Repository not found or private");
		assertThat(review.getFinishedAt()).isEqualTo(later);
		assertThat(review.getAnalysis()).isNull();
	}

	@Test
	void errorMessageIsCutToWhatTheColumnHolds() {
		Review review = running();

		review.fail("INTERNAL_ERROR", "x".repeat(Review.MAX_ERROR_MESSAGE_LENGTH + 1), NOW);

		assertThat(review.getErrorMessage()).hasSize(Review.MAX_ERROR_MESSAGE_LENGTH);
	}

	@Test
	void failingWithoutAMessageIsAllowed() {
		Review review = running();

		review.fail("INTERNAL_ERROR", null, NOW);

		assertThat(review.getErrorMessage()).isNull();
	}

	@ParameterizedTest
	@MethodSource("blankCodes")
	void failingNeedsAnErrorCode(String code) {
		Review review = running();

		assertThatThrownBy(() -> review.fail(code, "message", NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThat(review.getStatus()).isEqualTo(ReviewStatus.RUNNING);
	}

	static Stream<String> blankCodes() {
		return Stream.of(null, "", " ");
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidProgress")
	void progressMustStayWithinTheTotal(String description, int done, int total) {
		Review review = running();
		review.analyzing(3);

		assertThatThrownBy(() -> review.progress(done, total)).isInstanceOf(IllegalArgumentException.class);
		assertThat(review.getChunksDone()).isZero();
		assertThat(review.getChunksTotal()).isEqualTo(3);
	}

	static Stream<Arguments> invalidProgress() {
		return Stream.of(Arguments.of("more done than there are", 4, 3), Arguments.of("a negative number done", -1, 3));
	}

	@Test
	void analysisCannotStartWithANegativeNumberOfChunks() {
		Review review = running();

		assertThatThrownBy(() -> review.analyzing(-1)).isInstanceOf(IllegalArgumentException.class);
		assertThat(review.getStage()).isEqualTo(ReviewStage.FETCHING);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("transitionsOfARunningReview")
	void queuedReviewMustBeStartedFirst(String description, Consumer<Review> transition) {
		Review review = new Review(1, REPO);

		assertRefused(review, transition, "QUEUED");
		assertThat(review.getStatus()).isEqualTo(ReviewStatus.QUEUED);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("allTransitions")
	void completedReviewNeverChanges(String description, Consumer<Review> transition) {
		Review review = running();
		review.complete(analysis(), false, NOW);

		assertRefused(review, transition, "COMPLETED");
		assertThat(review.getStatus()).isEqualTo(ReviewStatus.COMPLETED);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("allTransitions")
	void failedReviewNeverChanges(String description, Consumer<Review> transition) {
		Review review = running();
		review.fail("REPO_NOT_FOUND", "Repository not found or private", NOW);

		assertRefused(review, transition, "FAILED");
		assertThat(review.getStatus()).isEqualTo(ReviewStatus.FAILED);
		assertThat(review.getErrorCode()).isEqualTo("REPO_NOT_FOUND");
	}

	@Test
	void runningReviewCannotBeStartedAgain() {
		Review review = running();

		assertRefused(review, r -> r.start(NOW), "RUNNING (FETCHING)");
		assertThat(review.getAttempts()).isEqualTo(1);
	}

	@ParameterizedTest
	@MethodSource("stagesWithoutProgress")
	void progressIsReportedOnlyWhileAnalysing(Consumer<Review> stage) {
		Review review = running();
		stage.accept(review);

		assertThatThrownBy(() -> review.progress(0, 0)).isInstanceOf(IllegalStateException.class);
	}

	static Stream<Consumer<Review>> stagesWithoutProgress() {
		return Stream.<Consumer<Review>>of(review -> {
		}, Review::scoring);
	}

	static Stream<Arguments> transitionsOfARunningReview() {
		return Stream.of(Arguments.of("fetched", change(review -> review.fetched("main", SHA))),
				Arguments.of("analyzing", change(review -> review.analyzing(2))),
				Arguments.of("progress", change(review -> review.progress(1, 2))),
				Arguments.of("scoring", change(Review::scoring)),
				Arguments.of("complete", change(review -> review.complete(analysis(), false, NOW))),
				Arguments.of("requeue", change(review -> review.requeue(NOW))),
				Arguments.of("fail", change(review -> review.fail("INTERNAL_ERROR", "message", NOW))));
	}

	static Stream<Arguments> allTransitions() {
		return Stream.concat(Stream.of(Arguments.of("start", change(review -> review.start(NOW)))),
				transitionsOfARunningReview());
	}

	private static void assertRefused(Review review, Consumer<Review> transition, String state) {
		assertThatThrownBy(() -> transition.accept(review)).isInstanceOf(IllegalStateException.class)
				.hasMessageEndingWith("a review that is " + state);
	}

	// Gives the lambda a target type inside Arguments.of, which takes plain objects.
	private static Consumer<Review> change(Consumer<Review> change) {
		return change;
	}

	private static Review running() {
		Review review = new Review(1, REPO);
		review.start(NOW);
		return review;
	}

	private static Analysis analysis() {
		return new Analysis(REPO, SHA, "FAKE", "fake-1", 1, 72, 0, 100, 85, 12, 3, 0, 1840, 1);
	}

}
