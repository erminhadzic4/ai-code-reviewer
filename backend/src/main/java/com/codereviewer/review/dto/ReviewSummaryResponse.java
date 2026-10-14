package com.codereviewer.review.dto;

import java.time.Instant;
import java.util.UUID;

import com.codereviewer.review.ReviewStatus;

/**
 * One row of the review history.
 *
 * @param repo {@code owner/name} as it was submitted
 * @param branch {@code null} until the job has asked GitHub
 * @param commitSha {@code null} until the job has asked GitHub
 * @param overallScore set only for a completed review
 */
public record ReviewSummaryResponse(UUID id, String repo, String branch, String commitSha, ReviewStatus status,
		Integer overallScore, Instant createdAt, Instant finishedAt) {
}
