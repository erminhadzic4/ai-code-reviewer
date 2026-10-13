package com.codereviewer.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class MutableClockTest {

	private final MutableClock clock = new MutableClock();

	@Test
	void followsTheRealTimeUntilItIsMoved() {
		Instant before = Instant.now();

		Instant shown = clock.instant();

		assertThat(shown).isBetween(before, Instant.now());
		assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
	}

	@Test
	void advancingMovesItAheadOfTheRealTime() {
		clock.advance(Duration.ofMinutes(5));
		clock.advance(Duration.ofMinutes(1));

		assertThat(clock.instant()).isAfterOrEqualTo(Instant.now().plus(Duration.ofMinutes(6)).minusSeconds(1))
				.isBeforeOrEqualTo(Instant.now().plus(Duration.ofMinutes(6)));
	}

	@Test
	void neverGoesBack() {
		clock.advance(Duration.ofHours(1));
		Instant moved = clock.instant();

		assertThatThrownBy(() -> clock.advance(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
		assertThat(clock.instant()).isAfterOrEqualTo(moved);
	}

}
