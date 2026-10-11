package com.codereviewer.github;

import java.time.Instant;
import java.util.Optional;

import com.codereviewer.common.ApiException;

/**
 * A failure to read a repository from GitHub. Besides the HTTP status and message of every
 * {@link ApiException}, it carries a code and, for a rate limit, the time to try again.
 * Nothing is retried here; the caller decides.
 */
public class GitHubException extends ApiException {

	private final GitHubErrorCode code;
	private final Instant retryAt;

	public GitHubException(GitHubErrorCode code, String detail) {
		this(code, detail, null, null);
	}

	public GitHubException(GitHubErrorCode code, String detail, Throwable cause) {
		this(code, detail, null, cause);
	}

	public GitHubException(GitHubErrorCode code, String detail, Instant retryAt) {
		this(code, detail, retryAt, null);
	}

	private GitHubException(GitHubErrorCode code, String detail, Instant retryAt, Throwable cause) {
		super(code.status(), detail);
		this.code = code;
		this.retryAt = retryAt;
		if (cause != null) {
			initCause(cause);
		}
	}

	public GitHubErrorCode getCode() {
		return code;
	}

	public boolean isRetryable() {
		return code.retryable();
	}

	/** When GitHub said the limit resets; empty when it gave no time. */
	public Optional<Instant> getRetryAt() {
		return Optional.ofNullable(retryAt);
	}

}
