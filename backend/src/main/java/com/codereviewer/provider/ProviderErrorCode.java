package com.codereviewer.provider;

import org.springframework.http.HttpStatus;

/**
 * What went wrong while a provider reviewed a chunk. The name is stored with a failed
 * review; the {@code retryable} flag says whether sending the same chunk again soon can help.
 */
public enum ProviderErrorCode {

	/** Too many requests in a short time. */
	PROVIDER_RATE_LIMITED(HttpStatus.SERVICE_UNAVAILABLE, true),

	// Not retryable although it passes by itself: the quota comes back once a day, far
	// later than any retry of a job.
	/** The daily quota is used up. */
	PROVIDER_QUOTA_EXHAUSTED(HttpStatus.SERVICE_UNAVAILABLE, false),

	/** Server error, network failure, timeout or an answer that cannot be read. */
	PROVIDER_UNAVAILABLE(HttpStatus.BAD_GATEWAY, true),

	/** The provider rejected the configured API key. */
	PROVIDER_AUTH_FAILED(HttpStatus.BAD_GATEWAY, false),

	/** The provider rejected the request itself, for example an unknown model. */
	PROVIDER_MISCONFIGURED(HttpStatus.BAD_GATEWAY, false),

	/** The provider refused to answer for this content. */
	PROVIDER_BLOCKED(HttpStatus.UNPROCESSABLE_CONTENT, false),

	// Not retryable: the same chunk produces the same overlong answer.
	/** The answer was cut off at the output limit; nothing of it is used. */
	PROVIDER_OUTPUT_TRUNCATED(HttpStatus.BAD_GATEWAY, false),

	/** The answer arrived complete but is not the agreed JSON. */
	PROVIDER_INVALID_RESPONSE(HttpStatus.BAD_GATEWAY, true);

	private final HttpStatus status;
	private final boolean retryable;

	ProviderErrorCode(HttpStatus status, boolean retryable) {
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
