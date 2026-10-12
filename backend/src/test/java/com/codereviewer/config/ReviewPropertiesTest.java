package com.codereviewer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codereviewer.provider.ProviderType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewPropertiesTest {

	@Test
	void validSettingsAreAccepted() {
		ReviewProperties properties = new ReviewProperties(ProviderType.FAKE, 200_000);

		assertThat(properties.provider()).isEqualTo(ProviderType.FAKE);
		assertThat(properties.chunkBudget()).isEqualTo(200_000);
	}

	@Test
	void missingProviderIsRejected() {
		assertThatThrownBy(() -> new ReviewProperties(null, 200_000)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("REVIEW_PROVIDER is empty");
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, -1 })
	void chunkBudgetMustBePositive(int budget) {
		assertThatThrownBy(() -> new ReviewProperties(ProviderType.FAKE, budget))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("app.review.chunk-budget must be positive.");
	}

}
