package com.codereviewer.provider;

/**
 * How much a provider accepts in one minute. A limit belongs to a provider and its model, so
 * the provider states it and the job only keeps to it.
 *
 * @param requestsPerMinute most requests in any 60 seconds
 * @param charactersPerMinute most characters of source, line numbers included, in any 60
 * seconds; the same measure as the budget of a chunk
 */
public record ProviderLimits(int requestsPerMinute, int charactersPerMinute) {

	/** For a provider that never has to be slowed down. */
	public static final ProviderLimits UNLIMITED = new ProviderLimits(Integer.MAX_VALUE, Integer.MAX_VALUE);

	public ProviderLimits {
		if (requestsPerMinute <= 0 || charactersPerMinute <= 0) {
			throw new IllegalArgumentException("A limit must be positive");
		}
	}

	public boolean isUnlimited() {
		return equals(UNLIMITED);
	}

}
