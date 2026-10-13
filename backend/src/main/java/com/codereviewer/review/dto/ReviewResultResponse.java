package com.codereviewer.review.dto;

import java.util.List;

import com.codereviewer.provider.FindingCategory;
import com.codereviewer.provider.Severity;
import com.codereviewer.review.FindingLevel;

/**
 * The report of a completed review.
 *
 * @param findings the issues, in the order of the files
 * @param goodPractices what the code does well; these never affect a score
 */
public record ReviewResultResponse(Scores scores, Counts counts, Stats stats, List<Finding> findings,
		List<Finding> goodPractices) {

	public ReviewResultResponse {
		findings = List.copyOf(findings);
		goodPractices = List.copyOf(goodPractices);
	}

	/** Each score is between 0 and 100 and computed by the backend from the findings. */
	public record Scores(int overall, int security, int maintainability, int performance) {
	}

	/** The four counters of the report; every issue is in exactly one of the first three. */
	public record Counts(int critical, int warnings, int codeSmells, int goodPractices) {
	}

	/**
	 * How much of the repository the report covers.
	 *
	 * @param filesSkipped files left out before the review, such as documentation and lock files
	 * @param filesFailed files the model could not review; above zero the report is partial
	 * @param linesAnalyzed lines of the reviewed files, which the scores were computed from
	 * @param findingsDiscarded findings dropped because they did not point at real code
	 */
	public record Stats(int filesAnalyzed, int filesSkipped, int filesFailed, int linesAnalyzed,
			int findingsDiscarded) {
	}

	/**
	 * @param level the counter the finding belongs to, derived from kind and severity
	 * @param severity {@code null} for a good practice
	 * @param suggestedCode replacement for the lines {@code lineStart..lineEnd}
	 * @param codeSnippet the lines the finding covers with their context, from the file itself
	 * @param snippetStartLine line number of the first line of {@code codeSnippet}
	 */
	public record Finding(long id, FindingLevel level, FindingCategory category, Severity severity, String filePath,
			Integer lineStart, Integer lineEnd, String title, String explanation, String suggestion,
			String suggestedCode, String codeSnippet, Integer snippetStartLine) {
	}

}
