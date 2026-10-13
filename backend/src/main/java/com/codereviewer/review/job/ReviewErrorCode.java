package com.codereviewer.review.job;

/**
 * Why a review failed when neither GitHub nor the provider is to blame. The name is stored
 * with the failed review, next to the codes of {@code GitHubErrorCode} and
 * {@code ProviderErrorCode}. None of these can be fixed by trying again.
 */
public enum ReviewErrorCode {

	/** The repository was read, but the file filter left nothing to review. */
	NO_REVIEWABLE_FILES,

	/** The application stopped while the review was running, on every attempt it had. */
	REVIEW_INTERRUPTED,

	/** A mistake in this application; the details are in the log only. */
	INTERNAL_ERROR

}
