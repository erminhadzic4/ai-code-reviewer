package com.codereviewer.provider;

import java.time.Instant;
import java.util.Optional;

import com.codereviewer.common.ApiException;

/**
 * A failure of a provider to review a chunk, the same for every provider. Besides the HTTP
 * status and message of every {@link ApiException}, it carries a code and, when the provider
 * gave one, the time to try again. Nothing is retried here; the caller decides.
 */
public class ProviderException extends ApiException {

	private final ProviderErrorCode code;
	private final Instant retryAt;

	public ProviderException(ProviderErrorCode code, String detail) {
		this(code, detail, null, null);
	}

	public ProviderException(ProviderErrorCode code, String detail, Throwable cause) {
		this(code, detail, null, cause);
	}

	public ProviderException(ProviderErrorCode code, String detail, Instant retryAt) {
		this(code, detail, retryAt, null);
	}

	private ProviderException(ProviderErrorCode code, String detail, Instant retryAt, Throwable cause) {
		super(code.status(), detail);
		this.code = code;
		this.retryAt = retryAt;
		if (cause != null) {
			initCause(cause);
		}
	}

	public ProviderErrorCode getCode() {
		return code;
	}

	public boolean isRetryable() {
		return code.retryable();
	}

	/** When the provider said to try again; empty when it gave no time. */
	public Optional<Instant> getRetryAt() {
		return Optional.ofNullable(retryAt);
	}

}
