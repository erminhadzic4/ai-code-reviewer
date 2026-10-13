package com.codereviewer.review.job;

import java.time.Duration;

/**
 * Waits. Everything in a job that has to wait does it through this interface, so a test can
 * move a clock forward instead of spending the time.
 */
@FunctionalInterface
public interface Sleeper {

	/**
	 * Returns after {@code duration} has passed; at once if it is zero or negative.
	 *
	 * @throws InterruptedException if the thread is interrupted, which is how a shutdown stops
	 * a job that is waiting
	 */
	void sleep(Duration duration) throws InterruptedException;

}
