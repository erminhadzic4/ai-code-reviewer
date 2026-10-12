package com.codereviewer.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codereviewer.provider.FindingKind;
import com.codereviewer.provider.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class FindingLevelTest {

	@ParameterizedTest(name = "{0} {1} is {2}")
	@CsvSource({
			"ISSUE, CRITICAL, CRITICAL",
			"ISSUE, HIGH, WARNING",
			"ISSUE, MEDIUM, WARNING",
			"ISSUE, LOW, SMELL",
			"GOOD_PRACTICE, , GOOD_PRACTICE" })
	void levelFollowsFromKindAndSeverity(FindingKind kind, Severity severity, FindingLevel expected) {
		assertThat(FindingLevel.of(kind, severity)).isEqualTo(expected);
	}

	@ParameterizedTest
	@EnumSource(Severity.class)
	void anIssueIsNeverCountedAsAGoodPractice(Severity severity) {
		assertThat(FindingLevel.of(FindingKind.ISSUE, severity)).isNotEqualTo(FindingLevel.GOOD_PRACTICE);
	}

	@Test
	void anIssueWithoutASeverityHasNoLevel() {
		assertThatThrownBy(() -> FindingLevel.of(FindingKind.ISSUE, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("An issue needs a severity");
	}

	@ParameterizedTest
	@EnumSource(Severity.class)
	void aGoodPracticeWithASeverityHasNoLevel(Severity severity) {
		assertThatThrownBy(() -> FindingLevel.of(FindingKind.GOOD_PRACTICE, severity))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("A good practice has no severity");
	}

}
