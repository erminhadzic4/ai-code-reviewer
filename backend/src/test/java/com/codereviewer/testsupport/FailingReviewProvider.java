package com.codereviewer.testsupport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.codereviewer.provider.CodeReviewProvider;
import com.codereviewer.provider.ProviderLimits;
import com.codereviewer.provider.RawFinding;
import com.codereviewer.provider.ReviewChunk;

/**
 * A provider that fails when a test tells it to and otherwise answers like the provider it
 * wraps. It also remembers every chunk it was asked to review.
 */
public final class FailingReviewProvider implements CodeReviewProvider {

	private final CodeReviewProvider delegate;
	private final Map<Integer, RuntimeException> failures = new HashMap<>();
	private final List<ReviewChunk> received = new ArrayList<>();

	public FailingReviewProvider(CodeReviewProvider delegate) {
		this.delegate = delegate;
	}

	/** Makes the call with this number, counted from 1 since the last reset, throw. */
	public synchronized void failOnCall(int call, RuntimeException failure) {
		failures.put(call, failure);
	}

	/** Every chunk since the last reset, failed calls included. */
	public synchronized List<ReviewChunk> received() {
		return List.copyOf(received);
	}

	public synchronized void reset() {
		failures.clear();
		received.clear();
	}

	@Override
	public String name() {
		return delegate.name();
	}

	@Override
	public String model() {
		return delegate.model();
	}

	@Override
	public int promptVersion() {
		return delegate.promptVersion();
	}

	@Override
	public ProviderLimits limits() {
		return delegate.limits();
	}

	@Override
	public synchronized List<RawFinding> review(ReviewChunk chunk) {
		received.add(chunk);
		RuntimeException failure = failures.get(received.size());
		if (failure != null) {
			throw failure;
		}
		return delegate.review(chunk);
	}

}
