package com.codereviewer.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock a test can move forward: the real time plus an offset that only grows.
 *
 * <p>It follows the real time instead of standing still because some timestamps are taken
 * from the system clock (a new review's {@code created_at}); this clock is never behind them.
 * It cannot go back, so one instance can serve every test that shares an application context.
 */
public final class MutableClock extends Clock {

	private final Clock base = Clock.systemUTC();
	private Duration offset = Duration.ZERO;

	public synchronized void advance(Duration duration) {
		if (duration.isNegative()) {
			throw new IllegalArgumentException("This clock only moves forward");
		}
		offset = offset.plus(duration);
	}

	@Override
	public synchronized Instant instant() {
		return base.instant().plus(offset);
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		throw new UnsupportedOperationException("Tests use this clock in UTC only");
	}

}
