package com.codereviewer.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.codereviewer.IntegrationTest;
import com.codereviewer.review.job.Sleeper;
import com.codereviewer.review.job.ThreadSleeper;
import com.codereviewer.testsupport.MutableClock;
import com.codereviewer.testsupport.RecordingSleeper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** Code that waits gets the test doubles in a test and the real clock and sleeper otherwise. */
@IntegrationTest
class ClockIntegrationTest {

	@Autowired
	private Clock clock;

	@Autowired
	private Sleeper sleeper;

	@Autowired
	private RecordingSleeper recordingSleeper;

	@Autowired
	private ApplicationContext context;

	@BeforeEach
	void forgetEarlierWaits() {
		recordingSleeper.clear();
	}

	@Test
	void applicationHasAClockInUtcAndASleeperThatReallyWaits() {
		assertThat(context.getBean("clock", Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
		assertThat(context.getBean("clock", Clock.class)).isNotInstanceOf(MutableClock.class);
		assertThat(context.getBean(ThreadSleeper.class)).isNotNull();
	}

	@Test
	void testsGetTheClockAndTheSleeperTheyCanControl() {
		assertThat(clock).isInstanceOf(MutableClock.class);
		assertThat(sleeper).isSameAs(recordingSleeper);
	}

	@Test
	void waitingMovesTheInjectedClockWithoutTakingTime() throws InterruptedException {
		Instant realStart = Instant.now();
		Instant before = clock.instant();

		sleeper.sleep(Duration.ofHours(1));

		assertThat(clock.instant()).isAfterOrEqualTo(before.plus(Duration.ofHours(1)));
		assertThat(recordingSleeper.slept()).containsExactly(Duration.ofHours(1));
		assertThat(Duration.between(realStart, Instant.now())).isLessThan(Duration.ofSeconds(5));
	}

}
