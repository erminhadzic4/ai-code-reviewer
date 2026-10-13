package com.codereviewer.review.job;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

import com.codereviewer.provider.CodeReviewProvider;
import com.codereviewer.provider.ProviderLimits;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Keeps the requests to a provider within its limits. A provider never waits by itself, so
 * the job calls {@link #await(int)} before every request.
 *
 * <p>The limits hold for any 60 seconds, not for calendar minutes: a request counts against
 * them from the moment it is sent until 60 seconds later. A request is held back only as long
 * as it takes for both the number of requests and the number of characters to fit, so a
 * small review never waits.
 *
 * <p>What was sent is remembered in memory only. After a restart the limits can be crossed
 * for a moment; the provider then answers with a rate limit, which the job retries.
 */
@Component
public class RequestPacer {

	static final Duration WINDOW = Duration.ofSeconds(60);

	private final ProviderLimits limits;
	private final Clock clock;
	private final Sleeper sleeper;

	/** Requests of the last 60 seconds, oldest first. */
	private final Deque<Sent> sent = new ArrayDeque<>();

	@Autowired
	public RequestPacer(CodeReviewProvider provider, Clock clock, Sleeper sleeper) {
		this(provider.limits(), clock, sleeper);
	}

	RequestPacer(ProviderLimits limits, Clock clock, Sleeper sleeper) {
		this.limits = limits;
		this.clock = clock;
		this.sleeper = sleeper;
	}

	/**
	 * Returns when a request of this size may be sent, and counts it as sent at that moment.
	 *
	 * @param characters what the request costs: the characters of its chunk as they are sent
	 * @throws InterruptedException if the thread is interrupted while waiting; the request is
	 * then not counted
	 */
	public synchronized void await(int characters) throws InterruptedException {
		if (characters < 0) {
			throw new IllegalArgumentException("The size of a request cannot be negative");
		}
		if (limits.isUnlimited()) {
			return;
		}
		// Checked again after every wait: a sleep may end a little early.
		while (true) {
			Instant now = clock.instant();
			forgetRequestsOlderThanTheWindow(now);
			Instant earliest = earliestSend(now, characters);
			if (!earliest.isAfter(now)) {
				sent.addLast(new Sent(now, characters));
				return;
			}
			sleeper.sleep(Duration.between(now, earliest));
		}
	}

	private void forgetRequestsOlderThanTheWindow(Instant now) {
		while (!sent.isEmpty() && !sent.peekFirst().at().plus(WINDOW).isAfter(now)) {
			sent.removeFirst();
		}
	}

	// Walks through the remembered requests from the oldest: each one that has to leave the
	// window first moves the earliest moment to the end of its 60 seconds. A request larger
	// than the whole limit can never fit, so it goes as soon as the window is empty.
	private Instant earliestSend(Instant now, int characters) {
		int requests = sent.size();
		long used = sent.stream().mapToLong(Sent::characters).sum();
		Instant earliest = now;
		for (Sent oldest : sent) {
			boolean fits = requests < limits.requestsPerMinute() && used + characters <= limits.charactersPerMinute();
			if (fits) {
				break;
			}
			earliest = oldest.at().plus(WINDOW);
			requests--;
			used -= oldest.characters();
		}
		return earliest;
	}

	private record Sent(Instant at, int characters) {
	}

}
