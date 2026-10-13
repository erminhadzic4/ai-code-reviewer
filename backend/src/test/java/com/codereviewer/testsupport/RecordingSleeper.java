package com.codereviewer.testsupport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.codereviewer.review.job.Sleeper;

/**
 * A sleeper that takes no time: it moves the clock forward by the requested duration and
 * remembers it. To the code under test the time has passed; the test reads how long it
 * asked to wait.
 */
public final class RecordingSleeper implements Sleeper {

	private final MutableClock clock;
	private final List<Duration> slept = new ArrayList<>();

	public RecordingSleeper(MutableClock clock) {
		this.clock = clock;
	}

	// Like Thread.sleep, a duration that is not positive is no wait at all.
	@Override
	public synchronized void sleep(Duration duration) {
		if (duration.isZero() || duration.isNegative()) {
			return;
		}
		clock.advance(duration);
		slept.add(duration);
	}

	/** Every wait so far, in order. */
	public synchronized List<Duration> slept() {
		return List.copyOf(slept);
	}

	public synchronized Duration total() {
		return slept.stream().reduce(Duration.ZERO, Duration::plus);
	}

	/** Forgets the waits recorded so far; the clock stays where it is. */
	public synchronized void clear() {
		slept.clear();
	}

}
