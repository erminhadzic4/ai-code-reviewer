package com.codereviewer.review.dto;

import java.time.Instant;
import java.util.UUID;

import com.codereviewer.review.ReviewStage;
import com.codereviewer.review.ReviewStatus;

/**
 * Where a review stands. Small on purpose: a client asks for it every few seconds.
 *
 * @param repo {@code owner/name} as it was submitted
 * @param branch the default branch; {@code null} until the job has asked GitHub
 * @param commitSha the commit that is reviewed; {@code null} until the job has asked GitHub
 * @param stage what a running review is doing; {@code null} when it is not running
 * @param chunksDone parts of the repository reviewed so far
 * @param chunksTotal parts the repository was split into; 0 until that is known
 * @param attempt how many times the review has been started
 * @param maxAttempts how many times it is started at most
 * @param retryAt when a review that failed for a passing reason is tried again; set only
 * while it waits in the queue for that time
 * @param cacheHit whether the report comes from an earlier review of the same commit
 * @param error why a failed review failed; {@code null} otherwise
 */
public record ReviewStatusResponse(UUID id, String repo, String branch, String commitSha, ReviewStatus status,
		ReviewStage stage, int chunksDone, int chunksTotal, int attempt, int maxAttempts, Instant retryAt,
		boolean cacheHit, Instant createdAt, Instant startedAt, Instant finishedAt, Error error) {

	/**
	 * @param code a stable name for the kind of failure, for the client to act on
	 * @param message a sentence for the user
	 */
	public record Error(String code, String message) {
	}

}
