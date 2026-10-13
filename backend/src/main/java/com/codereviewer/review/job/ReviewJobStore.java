package com.codereviewer.review.job;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.codereviewer.config.ReviewProperties;
import com.codereviewer.github.RepoRef;
import com.codereviewer.provider.CodeReviewProvider;
import com.codereviewer.provider.VerifiedFinding;
import com.codereviewer.review.Analysis;
import com.codereviewer.review.AnalysisRepository;
import com.codereviewer.review.Finding;
import com.codereviewer.review.FindingRepository;
import com.codereviewer.review.Review;
import com.codereviewer.review.ReviewRepository;
import com.codereviewer.review.ReviewStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything a job writes to the database. Each method is one short transaction, so no
 * transaction, and no database connection, is held while the job talks to GitHub or to a
 * provider.
 *
 * <p>The methods live in a class of their own because a transaction starts only when a
 * method is called from outside, through the proxy Spring puts around this bean. The same
 * methods inside the class that runs the job, called by that class itself, would run without
 * one.
 */
@Component
public class ReviewJobStore {

	private static final Logger log = LoggerFactory.getLogger(ReviewJobStore.class);

	static final String INTERRUPTED_MESSAGE = "The review was interrupted every time it ran. Submit the repository "
			+ "again";

	private final ReviewRepository reviews;
	private final AnalysisRepository analyses;
	private final FindingRepository findings;
	private final int maxAttempts;

	public ReviewJobStore(ReviewRepository reviews, AnalysisRepository analyses, FindingRepository findings,
			ReviewProperties properties) {
		this.reviews = reviews;
		this.analyses = analyses;
		this.findings = findings;
		this.maxAttempts = properties.maxAttempts();
	}

	/**
	 * Takes the queued review that has waited longest and is due, and marks it as running.
	 * The returned review is a snapshot: later changes go through the other methods, by id.
	 */
	@Transactional
	public Optional<Review> claimNext(Instant now) {
		Optional<Review> next = reviews.findNextDue(now);
		next.ifPresent(review -> review.start(now));
		return next;
	}

	/**
	 * Deals with reviews that were running when the application stopped: nothing is running
	 * when it starts, so each of them goes back into the queue, due at once. A review that
	 * has used up its attempts fails instead, so a review that brings the application down
	 * cannot do so forever.
	 *
	 * @return the number of reviews found running
	 */
	@Transactional
	public int recover(Instant now) {
		List<Review> interrupted = reviews.findByStatus(ReviewStatus.RUNNING);
		for (Review review : interrupted) {
			if (review.getAttempts() >= maxAttempts) {
				review.fail(ReviewErrorCode.REVIEW_INTERRUPTED.name(), INTERRUPTED_MESSAGE, now);
				log.warn("Review {} was interrupted on attempt {} of {} and has failed", review.getId(),
						review.getAttempts(), maxAttempts);
			}
			else {
				review.requeue(now);
				log.info("Review {} was interrupted on attempt {} of {} and is queued again", review.getId(),
						review.getAttempts(), maxAttempts);
			}
		}
		return interrupted.size();
	}

	@Transactional
	public void markFetched(UUID reviewId, String branch, String commitSha) {
		load(reviewId).fetched(branch, commitSha);
	}

	@Transactional
	public void markAnalyzing(UUID reviewId, int chunksTotal) {
		load(reviewId).analyzing(chunksTotal);
	}

	@Transactional
	public void progress(UUID reviewId, int chunksDone, int chunksTotal) {
		load(reviewId).progress(chunksDone, chunksTotal);
	}

	@Transactional
	public void markScoring(UUID reviewId) {
		load(reviewId).scoring();
	}

	/** The analysis of this commit that this provider would produce, if it is stored already. */
	@Transactional(readOnly = true)
	public Optional<Analysis> findCached(RepoRef repo, String commitSha, CodeReviewProvider provider) {
		return analyses.findByCacheKey(repo.owner(), repo.name(), commitSha, provider.name(), provider.model(),
				provider.promptVersion());
	}

	/** Completes a review with an analysis that existed before it ran. */
	@Transactional
	public void completeFromCache(UUID reviewId, long analysisId, Instant now) {
		load(reviewId).complete(analyses.getReferenceById(analysisId), true, now);
	}

	/**
	 * Stores a new analysis with its findings and completes the review, all or nothing: an
	 * analysis is shared and never changes, so half of one must not become visible.
	 *
	 * @param analysis a new analysis, not stored yet
	 */
	@Transactional
	public void complete(UUID reviewId, Analysis analysis, List<VerifiedFinding> verified, Instant now) {
		Review review = load(reviewId);
		Analysis stored = analyses.save(analysis);
		findings.saveAll(verified.stream()
				.map(finding -> new Finding(stored, finding.finding(), finding.codeSnippet(),
						finding.snippetStartLine()))
				.toList());
		review.complete(stored, false, now);
	}

	/** Puts a running review back into the queue for another attempt. */
	@Transactional
	public void requeue(UUID reviewId, Instant runAfter) {
		load(reviewId).requeue(runAfter);
	}

	@Transactional
	public void fail(UUID reviewId, JobFailure failure, Instant now) {
		load(reviewId).fail(failure.code(), failure.message(), now);
	}

	private Review load(UUID reviewId) {
		return reviews.findById(reviewId)
				.orElseThrow(() -> new IllegalStateException("Review " + reviewId + " does not exist"));
	}

}
