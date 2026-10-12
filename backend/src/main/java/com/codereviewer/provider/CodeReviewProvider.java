package com.codereviewer.provider;

import java.util.List;

/**
 * Something that reads code and reports findings. The rest of the application depends only
 * on this interface, so it does not know whether a model or a fake did the review.
 */
public interface CodeReviewProvider {

	/** Stored with an analysis and part of the cache key, e.g. {@code FAKE}. */
	String name();

	/** The model behind the provider; also part of the cache key. */
	String model();

	/**
	 * Reviews one chunk. The findings are returned as reported; checking them is the
	 * caller's job.
	 */
	List<RawFinding> review(ReviewChunk chunk);

}
