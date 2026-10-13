package com.codereviewer.review.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import com.codereviewer.provider.ProviderLimits;
import com.codereviewer.testsupport.MutableClock;
import com.codereviewer.testsupport.RecordingSleeper;
import org.junit.jupiter.api.Test;

class RequestPacerTest {

	/** The limits the application ships with for Gemini. */
	private static final ProviderLimits LIMITS = new ProviderLimits(12, 600_000);

	private static final int FULL_CHUNK = 200_000;

	// The test clock follows the real time, so a wait is a few milliseconds short of the round
	// number: the time the test itself took to get there.
	private static final Duration TOLERANCE = Duration.ofSeconds(1);

	private final MutableClock clock = new MutableClock();
	private final RecordingSleeper sleeper = new RecordingSleeper(clock);
	private final RequestPacer pacer = new RequestPacer(LIMITS, clock, sleeper);

	@Test
	void threeFullChunksGoOutWithoutWaiting() throws InterruptedException {
		pacer.await(FULL_CHUNK);
		pacer.await(FULL_CHUNK);
		pacer.await(FULL_CHUNK);

		assertThat(sleeper.slept()).isEmpty();
	}

	@Test
	void fourthFullChunkWaitsUntilTheFirstIsAMinuteOld() throws InterruptedException {
		pacer.await(FULL_CHUNK);
		pacer.await(FULL_CHUNK);
		pacer.await(FULL_CHUNK);

		pacer.await(FULL_CHUNK);

		assertThat(sleeper.total()).isCloseTo(Duration.ofSeconds(60), TOLERANCE);
	}

	@Test
	void waitIsOnlyAsLongAsNeeded() throws InterruptedException {
		pacer.await(FULL_CHUNK);
		clock.advance(Duration.ofSeconds(20));
		pacer.await(FULL_CHUNK);
		clock.advance(Duration.ofSeconds(20));
		pacer.await(FULL_CHUNK);
		assertThat(sleeper.slept()).isEmpty();

		// The first chunk is 40 seconds old, so the fourth waits for the remaining 20.
		pacer.await(FULL_CHUNK);
		assertThat(sleeper.total()).isCloseTo(Duration.ofSeconds(20), TOLERANCE);

		// And the fifth for the second chunk, another 20 seconds: three chunks a minute.
		pacer.await(FULL_CHUNK);
		assertThat(sleeper.total()).isCloseTo(Duration.ofSeconds(40), TOLERANCE);
	}

	@Test
	void budgetReturnsWhenAMinuteHasPassed() throws InterruptedException {
		pacer.await(FULL_CHUNK);
		pacer.await(FULL_CHUNK);
		pacer.await(FULL_CHUNK);
		clock.advance(Duration.ofSeconds(61));

		pacer.await(FULL_CHUNK);
		pacer.await(FULL_CHUNK);
		pacer.await(FULL_CHUNK);

		assertThat(sleeper.slept()).isEmpty();
	}

	@Test
	void smallRequestsAreLimitedByTheirNumber() throws InterruptedException {
		for (int i = 0; i < 12; i++) {
			pacer.await(1_000);
		}
		assertThat(sleeper.slept()).isEmpty();

		pacer.await(1_000);

		assertThat(sleeper.total()).isCloseTo(Duration.ofSeconds(60), TOLERANCE);
	}

	@Test
	void requestWithoutContentStillCountsAsARequest() throws InterruptedException {
		for (int i = 0; i < 12; i++) {
			pacer.await(0);
		}

		pacer.await(0);

		assertThat(sleeper.total()).isCloseTo(Duration.ofSeconds(60), TOLERANCE);
	}

	@Test
	void laterOfTheTwoLimitsDecides() throws InterruptedException {
		// Eleven small requests now and one full chunk 30 seconds later: first the number of
		// requests holds the next one back, later the number of characters does.
		for (int i = 0; i < 11; i++) {
			pacer.await(1_000);
		}
		clock.advance(Duration.ofSeconds(30));
		pacer.await(FULL_CHUNK);
		assertThat(sleeper.slept()).isEmpty();

		// Thirteenth request: waits for the oldest small one, 30 seconds.
		pacer.await(FULL_CHUNK);
		assertThat(sleeper.total()).isCloseTo(Duration.ofSeconds(30), TOLERANCE);

		pacer.await(FULL_CHUNK);
		assertThat(sleeper.total()).isCloseTo(Duration.ofSeconds(30), TOLERANCE);

		// Three full chunks are in the window now; the fourth waits for the first of them.
		pacer.await(FULL_CHUNK);
		assertThat(sleeper.total()).isCloseTo(Duration.ofSeconds(60), TOLERANCE);
	}

	@Test
	void chunkLargerThanTheWholeLimitGoesOutWhenNothingElseIsCounted() throws InterruptedException {
		pacer.await(700_000);

		assertThat(sleeper.slept()).isEmpty();
	}

	@Test
	void chunkLargerThanTheWholeLimitWaitsForEverythingBeforeIt() throws InterruptedException {
		pacer.await(1_000);
		clock.advance(Duration.ofSeconds(10));
		pacer.await(1_000);

		// Until the younger of the two is a minute old.
		pacer.await(700_000);

		assertThat(sleeper.total()).isCloseTo(Duration.ofSeconds(60), TOLERANCE);
	}

	@Test
	void nothingElseFitsNextToAChunkLargerThanTheLimit() throws InterruptedException {
		pacer.await(700_000);

		pacer.await(1_000);

		assertThat(sleeper.total()).isCloseTo(Duration.ofSeconds(60), TOLERANCE);
	}

	@Test
	void requestThatHadToWaitIsCountedFromTheMomentItIsSent() throws InterruptedException {
		pacer.await(FULL_CHUNK);
		pacer.await(FULL_CHUNK);
		pacer.await(FULL_CHUNK);
		pacer.await(FULL_CHUNK);
		sleeper.clear();
		// The fourth chunk went out a minute after the first; 50 seconds later it still counts.
		clock.advance(Duration.ofSeconds(50));

		pacer.await(FULL_CHUNK);
		pacer.await(FULL_CHUNK);
		assertThat(sleeper.slept()).isEmpty();

		pacer.await(FULL_CHUNK);
		assertThat(sleeper.total()).isCloseTo(Duration.ofSeconds(10), TOLERANCE);
	}

	@Test
	void providerWithoutLimitsNeverWaits() throws InterruptedException {
		RequestPacer unlimited = new RequestPacer(ProviderLimits.UNLIMITED, clock, sleeper);

		for (int i = 0; i < 100; i++) {
			unlimited.await(2_000_000);
		}

		assertThat(sleeper.slept()).isEmpty();
	}

	@Test
	void sizeOfARequestCannotBeNegative() {
		assertThatThrownBy(() -> pacer.await(-1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void interruptedWaitIsNotCountedAsARequest() throws InterruptedException {
		boolean[] interrupt = { true };
		Sleeper interruptedOnce = duration -> {
			if (interrupt[0]) {
				interrupt[0] = false;
				throw new InterruptedException();
			}
			sleeper.sleep(duration);
		};
		RequestPacer interrupted = new RequestPacer(new ProviderLimits(1, 600_000), clock, interruptedOnce);
		interrupted.await(1_000);

		assertThatThrownBy(() -> interrupted.await(1_000)).isInstanceOf(InterruptedException.class);

		// Had the interrupted request been counted, this one would need two waits.
		interrupted.await(1_000);
		assertThat(sleeper.slept()).hasSize(1);
		assertThat(sleeper.total()).isCloseTo(Duration.ofSeconds(60), TOLERANCE);
	}

}
