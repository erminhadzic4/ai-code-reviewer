package com.codereviewer.review.job;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import com.codereviewer.github.GitHubException;
import com.codereviewer.provider.ProviderException;
import com.codereviewer.review.Review;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs queued reviews, one at a time, and records how each attempt ends. What a review does
 * is in {@link ReviewPipeline}; this class decides which review runs next and what happens
 * when one fails: another attempt later, or the end.
 *
 * <p>One review at a time is all the control there is: the caller runs on a single thread
 * and does not call again before the previous call has returned.
 */
@Component
public class ReviewWorker {

	static final String INTERNAL_ERROR_MESSAGE = "The review failed because of an internal error";

	private static final Logger log = LoggerFactory.getLogger(ReviewWorker.class);

	private final ReviewJobStore store;
	private final ReviewPipeline pipeline;
	private final RetryPolicy retryPolicy;
	private final Clock clock;

	private boolean recovered;

	public ReviewWorker(ReviewJobStore store, ReviewPipeline pipeline, RetryPolicy retryPolicy, Clock clock) {
		this.store = store;
		this.pipeline = pipeline;
		this.retryPolicy = retryPolicy;
		this.clock = clock;
	}

	/**
	 * One round of the worker: at most one review. The first round first deals with reviews
	 * that a previous run of the application left unfinished, and so does the round after one
	 * that ended with an exception.
	 */
	public void tick() {
		try {
			if (!recovered) {
				recover();
			}
			runOnce();
		}
		catch (RuntimeException ex) {
			// The round broke off, for example because the database could not be reached when
			// the end of a review was to be written. That review may still be marked as running
			// although nobody runs it, so the next round recovers again. This is safe because
			// everything here happens on one thread: when a round begins, this worker is not in
			// the middle of any review, so a review that is marked as running is a leftover.
			// (As everywhere, this assumes a single instance of the application.)
			recovered = false;
			throw ex;
		}
	}

	/**
	 * Returns reviews that were running when the application stopped to the queue. Runs on
	 * the same thread as the reviews, so it cannot touch one that is running now.
	 *
	 * @return the number of reviews found running
	 */
	public int recover() {
		int interrupted = store.recover(clock.instant());
		recovered = true;
		return interrupted;
	}

	/**
	 * Takes the next review that is due and runs it to its end.
	 *
	 * @return whether there was a review to run
	 */
	public boolean runOnce() {
		Optional<Review> claimed = store.claimNext(clock.instant());
		if (claimed.isEmpty()) {
			return false;
		}
		Review review = claimed.get();
		log.info("Review {} of {}/{} started, attempt {}", review.getId(), review.getRepoOwner(), review.getRepoName(),
				review.getAttempts());
		try {
			pipeline.run(review);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			leaveForRecovery(review);
		}
		catch (GitHubException ex) {
			failed(review, JobFailure.of(ex));
		}
		catch (ProviderException ex) {
			failed(review, JobFailure.of(ex));
		}
		catch (ReviewFailedException ex) {
			failed(review, JobFailure.of(ex));
		}
		catch (RuntimeException ex) {
			// A mistake in this application: the same review would hit it again, and what went
			// wrong is not something to show a user.
			log.error("Review {} failed unexpectedly", review.getId(), ex);
			failed(review, JobFailure.of(ReviewErrorCode.INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE));
		}
		return true;
	}

	private void failed(Review review, JobFailure failure) {
		// A shutdown interrupts the thread, and a request that was under way then fails like a
		// network error. That says nothing about the review, so it is not counted against it.
		if (Thread.currentThread().isInterrupted()) {
			leaveForRecovery(review);
			return;
		}
		Instant now = clock.instant();
		Optional<Instant> nextAttempt = retryPolicy.nextAttempt(failure, review.getAttempts(), now);
		if (nextAttempt.isPresent()) {
			store.requeue(review.getId(), failure, nextAttempt.get());
			log.warn("Review {} failed on attempt {} with {}; next attempt at {}", review.getId(), review.getAttempts(),
					failure.code(), nextAttempt.get());
		}
		else {
			store.fail(review.getId(), failure, now);
			log.warn("Review {} failed on attempt {} with {}: {}", review.getId(), review.getAttempts(), failure.code(),
					failure.message());
		}
	}

	// Nothing is written: the review stays as it is and is queued again when the application
	// starts the next time.
	private void leaveForRecovery(Review review) {
		log.info("Review {} was interrupted; it will be queued again on the next start", review.getId());
	}

}
