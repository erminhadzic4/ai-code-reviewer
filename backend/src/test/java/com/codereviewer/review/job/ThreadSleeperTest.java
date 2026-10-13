package com.codereviewer.review.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ThreadSleeperTest {

	private final ThreadSleeper sleeper = new ThreadSleeper();

	@AfterEach
	void clearInterruptFlag() {
		Thread.interrupted();
	}

	@Test
	void waitsForTheRequestedTime() throws InterruptedException {
		long start = System.nanoTime();

		sleeper.sleep(Duration.ofMillis(50));

		// Not the full 50: a timer may wake a thread slightly early on some systems.
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(40));
	}

	@Test
	void waitThatIsNotPositiveReturnsAtOnce() {
		assertThatCode(() -> sleeper.sleep(Duration.ZERO)).doesNotThrowAnyException();
		assertThatCode(() -> sleeper.sleep(Duration.ofSeconds(-1))).doesNotThrowAnyException();
	}

	// How a shutdown stops a job that is waiting: the wait ends with an exception, not quietly.
	@Test
	void interruptedThreadDoesNotWait() {
		Thread.currentThread().interrupt();

		assertThatThrownBy(() -> sleeper.sleep(Duration.ofMinutes(1))).isInstanceOf(InterruptedException.class);
	}

}
