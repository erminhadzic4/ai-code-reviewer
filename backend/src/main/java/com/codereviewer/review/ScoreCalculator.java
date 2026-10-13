package com.codereviewer.review;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

import com.codereviewer.provider.FindingCategory;
import com.codereviewer.provider.FindingKind;
import com.codereviewer.provider.RawFinding;
import com.codereviewer.provider.Severity;
import com.codereviewer.provider.VerifiedFinding;

/**
 * Computes the scores of an analysis from its findings. A score is never taken from a model.
 *
 * <p>Every issue costs its category a number of points that depends on its severity, per
 * {@value #LINES_PER_PENALTY} lines of code analysed: the same issues weigh half as much in
 * twice as much code. A category starts at 100 and cannot go below 0. The overall score is
 * a weighted average of the three category scores as they are shown, so it can be checked
 * by hand. Good practices change nothing; otherwise a score could be raised with praise.
 *
 * <p>The numbers are constants on purpose: changing one changes the score of every analysis
 * made afterwards, so it should go through a commit and a test.
 */
public final class ScoreCalculator {

	/** The amount of code the points of an issue refer to. */
	static final int LINES_PER_PENALTY = 1000;

	/**
	 * Less code than this is scored as if it were this much. Without it one minor issue in a
	 * forty-line script would cost most of the score.
	 */
	static final int MIN_LINES = 1000;

	static final int SECURITY_WEIGHT = 40;

	static final int MAINTAINABILITY_WEIGHT = 35;

	static final int PERFORMANCE_WEIGHT = 25;

	private ScoreCalculator() {
	}

	/**
	 * @param findings the findings that passed validation
	 * @param linesAnalyzed lines of the files that were actually reviewed
	 * @throws IllegalArgumentException for a negative number of lines or an issue without a
	 * severity, which validation does not let through
	 */
	public static Scores calculate(Collection<VerifiedFinding> findings, int linesAnalyzed) {
		if (linesAnalyzed < 0) {
			throw new IllegalArgumentException("The number of lines cannot be negative");
		}
		int lines = Math.max(linesAnalyzed, MIN_LINES);
		Map<FindingCategory, Integer> penalties = new EnumMap<>(FindingCategory.class);
		for (VerifiedFinding verified : findings) {
			RawFinding finding = verified.finding();
			if (finding.kind() == FindingKind.GOOD_PRACTICE) {
				continue;
			}
			penalties.merge(finding.category(), points(finding.severity()), Integer::sum);
		}
		int security = score(penalties.getOrDefault(FindingCategory.SECURITY, 0), lines);
		int maintainability = score(penalties.getOrDefault(FindingCategory.MAINTAINABILITY, 0), lines);
		int performance = score(penalties.getOrDefault(FindingCategory.PERFORMANCE, 0), lines);
		int weighted = SECURITY_WEIGHT * security + MAINTAINABILITY_WEIGHT * maintainability
				+ PERFORMANCE_WEIGHT * performance;
		return new Scores((weighted + 50) / 100, security, maintainability, performance);
	}

	/** Points an issue costs its category per {@value #LINES_PER_PENALTY} lines. */
	static int points(Severity severity) {
		if (severity == null) {
			throw new IllegalArgumentException("An issue needs a severity");
		}
		return switch (severity) {
			case CRITICAL -> 30;
			case HIGH -> 15;
			case MEDIUM -> 6;
			case LOW -> 3;
		};
	}

	// 100 - penalty * LINES_PER_PENALTY / lines, rounded half up, in whole numbers.
	private static int score(int penalty, int lines) {
		long remaining = 100L * lines - (long) penalty * LINES_PER_PENALTY;
		if (remaining <= 0) {
			return 0;
		}
		return (int) ((2 * remaining + lines) / (2L * lines));
	}

}
