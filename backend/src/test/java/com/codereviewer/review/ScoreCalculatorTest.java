package com.codereviewer.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import com.codereviewer.provider.FindingCategory;
import com.codereviewer.provider.FindingKind;
import com.codereviewer.provider.RawFinding;
import com.codereviewer.provider.Severity;
import com.codereviewer.provider.VerifiedFinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ScoreCalculatorTest {

	private static final Scores PERFECT = new Scores(100, 100, 100, 100);

	static Stream<Arguments> workedExamples() {
		List<VerifiedFinding> mixed = new ArrayList<>();
		mixed.add(issue(FindingCategory.SECURITY, Severity.CRITICAL));
		mixed.add(issue(FindingCategory.SECURITY, Severity.HIGH));
		mixed.addAll(issues(3, FindingCategory.MAINTAINABILITY, Severity.MEDIUM));
		mixed.addAll(issues(4, FindingCategory.MAINTAINABILITY, Severity.LOW));
		mixed.add(issue(FindingCategory.PERFORMANCE, Severity.MEDIUM));
		return Stream.of(
				Arguments.of("issues in every category", mixed, 2000, new Scores(85, 78, 85, 97)),
				Arguments.of("one minor issue in a small repository",
						List.of(issue(FindingCategory.MAINTAINABILITY, Severity.LOW)), 300,
						new Scores(99, 100, 97, 100)),
				Arguments.of("one critical issue in a small repository",
						List.of(issue(FindingCategory.SECURITY, Severity.CRITICAL)), 300,
						new Scores(88, 70, 100, 100)),
				Arguments.of("as many critical issues as one full chunk can report",
						issues(15, FindingCategory.SECURITY, Severity.CRITICAL), 6000,
						new Scores(70, 25, 100, 100)));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("workedExamples")
	void workedExample(String name, List<VerifiedFinding> findings, int lines, Scores expected) {
		assertThat(ScoreCalculator.calculate(findings, lines)).isEqualTo(expected);
	}

	@Test
	void codeWithoutFindingsScoresAHundredEverywhere() {
		assertThat(ScoreCalculator.calculate(List.of(), 5000)).isEqualTo(PERFECT);
	}

	@Test
	void goodPracticesDoNotChangeAScore() {
		List<VerifiedFinding> issues = List.of(issue(FindingCategory.SECURITY, Severity.HIGH));
		List<VerifiedFinding> withPraise = new ArrayList<>(issues);
		for (FindingCategory category : FindingCategory.values()) {
			withPraise.add(goodPractice(category));
		}

		assertThat(ScoreCalculator.calculate(withPraise, 1000)).isEqualTo(ScoreCalculator.calculate(issues, 1000));
		assertThat(ScoreCalculator.calculate(List.of(goodPractice(FindingCategory.SECURITY)), 1000))
				.isEqualTo(PERFECT);
	}

	@ParameterizedTest(name = "{0} costs {1} points per thousand lines")
	@CsvSource({ "CRITICAL, 30", "HIGH, 15", "MEDIUM, 6", "LOW, 3" })
	void severityDecidesWhatAnIssueCosts(Severity severity, int points) {
		Scores scores = ScoreCalculator.calculate(List.of(issue(FindingCategory.PERFORMANCE, severity)), 1000);

		assertThat(scores.performance()).isEqualTo(100 - points);
	}

	@ParameterizedTest
	@EnumSource(FindingCategory.class)
	void anIssueLowersOnlyTheScoreOfItsCategory(FindingCategory category) {
		Scores scores = ScoreCalculator.calculate(List.of(issue(category, Severity.CRITICAL)), 1000);

		assertThat(scores.security()).isEqualTo(category == FindingCategory.SECURITY ? 70 : 100);
		assertThat(scores.maintainability()).isEqualTo(category == FindingCategory.MAINTAINABILITY ? 70 : 100);
		assertThat(scores.performance()).isEqualTo(category == FindingCategory.PERFORMANCE ? 70 : 100);
	}

	@Test
	void theSameIssuesWeighHalfAsMuchInTwiceAsMuchCode() {
		List<VerifiedFinding> findings = issues(2, FindingCategory.SECURITY, Severity.CRITICAL);

		assertThat(ScoreCalculator.calculate(findings, 1000).security()).isEqualTo(40);
		assertThat(ScoreCalculator.calculate(findings, 2000).security()).isEqualTo(70);
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, 1, 40, 999, 1000 })
	void lessCodeThanTheMinimumIsScoredAsTheMinimum(int lines) {
		List<VerifiedFinding> findings = List.of(issue(FindingCategory.MAINTAINABILITY, Severity.HIGH));

		assertThat(ScoreCalculator.calculate(findings, lines).maintainability()).isEqualTo(85);
	}

	@Test
	void aScoreNeverGoesBelowZero() {
		Scores scores = ScoreCalculator.calculate(issues(15, FindingCategory.SECURITY, Severity.CRITICAL), 1000);

		assertThat(scores.security()).isZero();
		assertThat(scores.overall()).isEqualTo(60);
	}

	@Test
	void aScoreIsRoundedHalfUp() {
		// 45 points in 2000 lines is 22.5, which leaves 77.5.
		List<VerifiedFinding> findings = List.of(issue(FindingCategory.SECURITY, Severity.CRITICAL),
				issue(FindingCategory.SECURITY, Severity.HIGH));

		assertThat(ScoreCalculator.calculate(findings, 2000).security()).isEqualTo(78);
	}

	@Test
	void overallScoreIsComputedFromTheRoundedCategoryScores() {
		// 3 points in 1200 lines leaves 97.5, shown as 98. From the shown scores the overall
		// score is 98.5, so 99; from the exact ones it would be 98.125, so 98.
		List<VerifiedFinding> findings = List.of(issue(FindingCategory.SECURITY, Severity.LOW),
				issue(FindingCategory.MAINTAINABILITY, Severity.LOW));

		assertThat(ScoreCalculator.calculate(findings, 1200)).isEqualTo(new Scores(99, 98, 98, 100));
	}

	@Test
	void overallScoreIsAHalfUpRoundedWeightedAverage() {
		// 0.40 * 85 + 0.35 * 100 + 0.25 * 94 = 92.5
		List<VerifiedFinding> findings = List.of(issue(FindingCategory.SECURITY, Severity.HIGH),
				issue(FindingCategory.PERFORMANCE, Severity.MEDIUM));

		assertThat(ScoreCalculator.calculate(findings, 1000)).isEqualTo(new Scores(93, 85, 100, 94));
	}

	@Test
	void categoryWeightsAddUpToAHundred() {
		assertThat(ScoreCalculator.SECURITY_WEIGHT + ScoreCalculator.MAINTAINABILITY_WEIGHT
				+ ScoreCalculator.PERFORMANCE_WEIGHT).isEqualTo(100);
	}

	@Test
	void oneMoreIssueNeverRaisesAScore() {
		List<VerifiedFinding> findings = new ArrayList<>();
		Scores previous = ScoreCalculator.calculate(findings, 3000);
		for (int i = 0; i < 60; i++) {
			FindingCategory category = FindingCategory.values()[i % FindingCategory.values().length];
			Severity severity = Severity.values()[i % Severity.values().length];
			findings.add(issue(category, severity));
			Scores current = ScoreCalculator.calculate(findings, 3000);

			assertThat(current.security()).isLessThanOrEqualTo(previous.security());
			assertThat(current.maintainability()).isLessThanOrEqualTo(previous.maintainability());
			assertThat(current.performance()).isLessThanOrEqualTo(previous.performance());
			assertThat(current.overall()).isLessThanOrEqualTo(previous.overall());
			previous = current;
		}
	}

	@Test
	void moreCodeNeverLowersAScore() {
		List<VerifiedFinding> findings = new ArrayList<>(issues(4, FindingCategory.SECURITY, Severity.CRITICAL));
		findings.addAll(issues(7, FindingCategory.MAINTAINABILITY, Severity.MEDIUM));
		findings.add(issue(FindingCategory.PERFORMANCE, Severity.HIGH));
		Scores previous = ScoreCalculator.calculate(findings, 0);
		for (int lines = 100; lines <= 20_000; lines += 100) {
			Scores current = ScoreCalculator.calculate(findings, lines);

			assertThat(current.security()).isGreaterThanOrEqualTo(previous.security());
			assertThat(current.maintainability()).isGreaterThanOrEqualTo(previous.maintainability());
			assertThat(current.performance()).isGreaterThanOrEqualTo(previous.performance());
			assertThat(current.overall()).isGreaterThanOrEqualTo(previous.overall());
			previous = current;
		}
	}

	@Test
	void anIssueWithoutASeverityCannotBeScored() {
		List<VerifiedFinding> findings = List.of(issue(FindingCategory.SECURITY, null));

		assertThatThrownBy(() -> ScoreCalculator.calculate(findings, 1000))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("An issue needs a severity");
	}

	@Test
	void aNegativeNumberOfLinesIsRejected() {
		assertThatThrownBy(() -> ScoreCalculator.calculate(List.of(), -1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("The number of lines cannot be negative");
	}

	private static List<VerifiedFinding> issues(int count, FindingCategory category, Severity severity) {
		return Collections.nCopies(count, issue(category, severity));
	}

	private static VerifiedFinding issue(FindingCategory category, Severity severity) {
		RawFinding finding = new RawFinding(FindingKind.ISSUE, category, severity, "src/Main.java", 1, 1, "An issue",
				null, null, null);
		return new VerifiedFinding(finding, "class Main {", 1);
	}

	private static VerifiedFinding goodPractice(FindingCategory category) {
		RawFinding finding = new RawFinding(FindingKind.GOOD_PRACTICE, category, null, null, null, null,
				"A good practice", null, null, null);
		return new VerifiedFinding(finding, null, null);
	}

}
