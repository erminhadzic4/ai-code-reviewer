package com.codereviewer.review.job;

import java.time.Instant;

import com.codereviewer.github.GitHubException;
import com.codereviewer.provider.ProviderException;

/**
 * Why an attempt at a review failed, in one shape for every source. The exceptions of the
 * GitHub client and of a provider each carry a code of their own type; the job needs only
 * what they have in common.
 *
 * @param code name of the error code, stored with the review if it fails for good
 * @param message a message that is safe to show to the user
 * @param retryable whether another attempt later can succeed
 * @param retryAt when the service said to try again; {@code null} when it gave no time
 */
public record JobFailure(String code, String message, boolean retryable, Instant retryAt) {

	public static JobFailure of(GitHubException ex) {
		return new JobFailure(ex.getCode().name(), ex.getMessage(), ex.isRetryable(), ex.getRetryAt().orElse(null));
	}

	public static JobFailure of(ProviderException ex) {
		return new JobFailure(ex.getCode().name(), ex.getMessage(), ex.isRetryable(), ex.getRetryAt().orElse(null));
	}

	/** A failure of the review itself, which no later attempt can fix. */
	public static JobFailure of(ReviewErrorCode code, String message) {
		return new JobFailure(code.name(), message, false, null);
	}

}
