package com.codereviewer.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProviderLimitsTest {

	@Test
	void limitsOfAProviderAreNotUnlimited() {
		ProviderLimits limits = new ProviderLimits(12, 600_000);

		assertThat(limits.requestsPerMinute()).isEqualTo(12);
		assertThat(limits.charactersPerMinute()).isEqualTo(600_000);
		assertThat(limits.isUnlimited()).isFalse();
	}

	@Test
	void unlimitedSaysSo() {
		assertThat(ProviderLimits.UNLIMITED.isUnlimited()).isTrue();
	}

	@ParameterizedTest
	@CsvSource({ "0, 600000", "-1, 600000", "12, 0", "12, -1" })
	void limitMustBePositive(int requests, int characters) {
		assertThatThrownBy(() -> new ProviderLimits(requests, characters)).isInstanceOf(IllegalArgumentException.class);
	}

}
