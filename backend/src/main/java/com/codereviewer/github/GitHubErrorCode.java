package com.codereviewer.github;

import org.springframework.http.HttpStatus;

/**
 * What went wrong while talking to GitHub. The name is stored with a failed review; the
 * {@code retryable} flag says whether trying again later can help.
 */
public enum GitHubErrorCode {

	/** Missing or private; GitHub answers both the same way. */
	REPO_NOT_FOUND(HttpStatus.NOT_FOUND, false),

	/** Exists but GitHub refuses to serve it (blocked or access denied). */
	REPO_UNAVAILABLE(HttpStatus.NOT_FOUND, false),

	REPO_EMPTY(HttpStatus.UNPROCESSABLE_CONTENT, false),

	REPO_TOO_LARGE(HttpStatus.UNPROCESSABLE_CONTENT, false),

	// 503 and not 429: this server is limited by GitHub, the caller did nothing wrong.
	RATE_LIMITED(HttpStatus.SERVICE_UNAVAILABLE, true),

	/** GitHub rejected the configured token. */
	GITHUB_AUTH_FAILED(HttpStatus.BAD_GATEWAY, false),

	/** Server error, unexpected answer, network failure or timeout. */
	GITHUB_UNAVAILABLE(HttpStatus.BAD_GATEWAY, true),

	/** The downloaded archive is damaged or contains paths a repository cannot have. */
	ARCHIVE_INVALID(HttpStatus.BAD_GATEWAY, false);

	private final HttpStatus status;
	private final boolean retryable;

	GitHubErrorCode(HttpStatus status, boolean retryable) {
		this.status = status;
		this.retryable = retryable;
	}

	public HttpStatus status() {
		return status;
	}

	public boolean retryable() {
		return retryable;
	}

}
