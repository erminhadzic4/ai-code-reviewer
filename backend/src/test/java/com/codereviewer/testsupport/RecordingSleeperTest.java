package com.codereviewer.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class RecordingSleeperTest {

	private final MutableClock clock = new MutableClock();
	private final RecordingSleeper sleeper = new RecordingSleeper(clock);

	@Test
	void sleepingMovesTheClockInsteadOfWaiting() throws InterruptedException {
		Instant realStart = Instant.now();
		Instant before = clock.instant();

		sleeper.sleep(Duration.ofMinutes(10));

		assertThat(clock.instant()).isAfterOrEqualTo(before.plus(Duration.ofMinutes(10)));
		assertThat(Duration.between(realStart, Instant.now())).isLessThan(Duration.ofSeconds(5));
	}

	@Test
	void remembersEveryWaitInOrder() throws InterruptedException {
		sleeper.sleep(Duration.ofSeconds(5));
		sleeper.sleep(Duration.ofSeconds(20));

		assertThat(sleeper.slept()).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(20));
		assertThat(sleeper.total()).isEqualTo(Duration.ofSeconds(25));
	}

	@Test
	void waitThatIsNotPositiveIsNoWait() throws InterruptedException {
		Instant before = clock.instant();

		sleeper.sleep(Duration.ZERO);
		sleeper.sleep(Duration.ofSeconds(-3));

		assertThat(sleeper.slept()).isEmpty();
		assertThat(sleeper.total()).isZero();
		assertThat(clock.instant()).isBefore(before.plusSeconds(3));
	}

	@Test
	void interruptedThreadDoesNotWaitAsWithARealSleep() {
		Instant before = clock.instant();
		Thread.currentThread().interrupt();

		assertThatThrownBy(() -> sleeper.sleep(Duration.ofMinutes(1))).isInstanceOf(InterruptedException.class);

		// The flag is cleared by the failed sleep, as Thread.sleep does.
		assertThat(Thread.interrupted()).isFalse();
		assertThat(sleeper.slept()).isEmpty();
		assertThat(clock.instant()).isBefore(before.plus(Duration.ofMinutes(1)));
	}

	@Test
	void clearingForgetsTheWaitsButKeepsTheTime() throws InterruptedException {
		Instant before = clock.instant();
		sleeper.sleep(Duration.ofMinutes(1));

		sleeper.clear();

		assertThat(sleeper.slept()).isEmpty();
		assertThat(clock.instant()).isAfterOrEqualTo(before.plus(Duration.ofMinutes(1)));
	}

}
