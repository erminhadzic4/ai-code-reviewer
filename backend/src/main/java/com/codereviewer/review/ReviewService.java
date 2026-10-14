package com.codereviewer.review;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.codereviewer.auth.AuthenticatedUser;
import com.codereviewer.auth.AuthenticationRequiredException;
import com.codereviewer.config.ReviewProperties;
import com.codereviewer.github.InvalidRepoUrlException;
import com.codereviewer.github.RepoRef;
import com.codereviewer.github.RepoUrlParser;
import com.codereviewer.provider.FindingKind;
import com.codereviewer.review.dto.PageResponse;
import com.codereviewer.review.dto.ReviewResultResponse;
import com.codereviewer.review.dto.ReviewStatusResponse;
import com.codereviewer.review.dto.ReviewSummaryResponse;
import com.codereviewer.review.dto.SubmitReviewRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a user can do with reviews: submit one, follow it and read its report. Running a
 * review is not done here; submitting only puts it into the queue, where the worker finds it.
 *
 * <p>Every read is by id and owner together, so a user never sees another user's review.
 */
@Service
public class ReviewService {

	static final int MAX_PAGE_SIZE = 50;

	private final ReviewRepository reviews;
	private final FindingRepository findings;
	private final int maxAttempts;
	private final Clock clock;

	public ReviewService(ReviewRepository reviews, FindingRepository findings, ReviewProperties properties,
			Clock clock) {
		this.reviews = reviews;
		this.findings = findings;
		this.maxAttempts = properties.maxAttempts();
		this.clock = clock;
	}

	/**
	 * Queues a review. GitHub is not asked here: whether the repository exists shows in the
	 * status of the review once the job has looked.
	 *
	 * @throws InvalidRepoUrlException if the URL is not that of a GitHub repository
	 */
	public ReviewStatusResponse submit(AuthenticatedUser user, SubmitReviewRequest request) {
		RepoRef repo = RepoUrlParser.parse(request.repoUrl());
		try {
			return toStatus(reviews.saveAndFlush(new Review(user.id(), repo)));
		}
		catch (DataIntegrityViolationException ex) {
			// The only rule a new review can break is that its user exists: a token outlives
			// its user until it expires.
			throw new AuthenticationRequiredException();
		}
	}

	/**
	 * The user's reviews, newest first. A size above {@value #MAX_PAGE_SIZE} is lowered to it.
	 *
	 * @throws InvalidPageException if the page is below 0 or the size below 1
	 */
	@Transactional(readOnly = true)
	public PageResponse<ReviewSummaryResponse> history(AuthenticatedUser user, int page, int size) {
		if (page < 0 || size < 1) {
			throw new InvalidPageException();
		}
		int pageSize = Math.min(size, MAX_PAGE_SIZE);
		// The id breaks ties between reviews created in the same instant, so pages never overlap.
		Sort newestFirst = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
		Page<Review> found = reviews.findByUserId(user.id(), PageRequest.of(page, pageSize, newestFirst));
		return new PageResponse<>(found.map(ReviewService::toSummary).getContent(), page, pageSize,
				found.getTotalElements());
	}

	@Transactional(readOnly = true)
	public ReviewStatusResponse status(AuthenticatedUser user, UUID reviewId) {
		return toStatus(owned(user, reviewId));
	}

	/**
	 * @throws ReviewNotCompletedException if the review is still under way or has failed
	 */
	@Transactional(readOnly = true)
	public ReviewResultResponse result(AuthenticatedUser user, UUID reviewId) {
		Review review = owned(user, reviewId);
		if (review.getStatus() != ReviewStatus.COMPLETED) {
			throw new ReviewNotCompletedException(review.getStatus());
		}
		Analysis analysis = review.getAnalysis();
		List<ReviewResultResponse.Finding> all = findings.findByAnalysisIdOrderById(analysis.getId())
				.stream()
				.map(ReviewService::toResponse)
				.toList();
		ReviewResultResponse.Scores scores = new ReviewResultResponse.Scores(analysis.getOverallScore(),
				analysis.getSecurityScore(), analysis.getMaintainabilityScore(), analysis.getPerformanceScore());
		ReviewResultResponse.Counts counts = new ReviewResultResponse.Counts(count(all, FindingLevel.CRITICAL),
				count(all, FindingLevel.WARNING), count(all, FindingLevel.SMELL),
				count(all, FindingLevel.GOOD_PRACTICE));
		ReviewResultResponse.Stats stats = new ReviewResultResponse.Stats(analysis.getFilesAnalyzed(),
				analysis.getFilesSkipped(), analysis.getFilesFailed(), analysis.getLinesAnalyzed(),
				analysis.getFindingsDiscarded());
		return new ReviewResultResponse(scores, counts, stats,
				all.stream().filter(finding -> finding.level() != FindingLevel.GOOD_PRACTICE).toList(),
				all.stream().filter(finding -> finding.level() == FindingLevel.GOOD_PRACTICE).toList());
	}

	private Review owned(AuthenticatedUser user, UUID reviewId) {
		return reviews.findByIdAndUserId(reviewId, user.id()).orElseThrow(ReviewNotFoundException::new);
	}

	private ReviewStatusResponse toStatus(Review review) {
		return new ReviewStatusResponse(review.getId(), review.getRepoOwner() + "/" + review.getRepoName(),
				review.getBranch(), review.getCommitSha(), review.getStatus(), review.getStage(),
				review.getChunksDone(), review.getChunksTotal(), review.getAttempts(), maxAttempts, retryAt(review),
				review.isCacheHit(), review.getCreatedAt(), review.getStartedAt(), review.getFinishedAt(),
				review.getStatus() == ReviewStatus.FAILED
						? new ReviewStatusResponse.Error(review.getErrorCode(), review.getErrorMessage()) : null);
	}

	// Only a review that waits for a time still ahead has one. A new review and one put back
	// into the queue after a restart are due at once: they wait for the worker, not for a time.
	private Instant retryAt(Review review) {
		boolean waiting = review.getStatus() == ReviewStatus.QUEUED && review.getRunAfter().isAfter(clock.instant());
		return waiting ? review.getRunAfter() : null;
	}

	private static ReviewSummaryResponse toSummary(Review review) {
		Integer score = review.getStatus() == ReviewStatus.COMPLETED ? review.getAnalysis().getOverallScore() : null;
		return new ReviewSummaryResponse(review.getId(), review.getRepoOwner() + "/" + review.getRepoName(),
				review.getBranch(), review.getCommitSha(), review.getStatus(), score, review.getCreatedAt(),
				review.getFinishedAt());
	}

	private static ReviewResultResponse.Finding toResponse(Finding finding) {
		return new ReviewResultResponse.Finding(finding.getId(),
				FindingLevel.of(finding.getKind(), finding.getSeverity()), finding.getCategory(),
				finding.getSeverity(), finding.getFilePath(), finding.getLineStart(), finding.getLineEnd(),
				finding.getTitle(), finding.getExplanation(), finding.getSuggestion(), finding.getSuggestedCode(),
				finding.getCodeSnippet(), finding.getSnippetStartLine());
	}

	private static int count(List<ReviewResultResponse.Finding> all, FindingLevel level) {
		return (int) all.stream().filter(finding -> finding.level() == level).count();
	}

}
