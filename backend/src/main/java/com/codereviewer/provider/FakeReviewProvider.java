package com.codereviewer.provider;

import java.util.ArrayList;
import java.util.List;

import com.codereviewer.github.SourceFile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stands in for a model: no network, no API key, and the same chunk always gives the same
 * findings. Lets everything around a provider be built and tested without one.
 *
 * <p>The findings point at files and lines of the chunk, so they survive the same checks as
 * real ones. The file at position {@code i} gets the issue {@code i % 4}; with four files or
 * more every severity appears.
 */
@Component
@ConditionalOnProperty(name = "app.review.provider", havingValue = "fake")
public class FakeReviewProvider implements CodeReviewProvider {

	static final String NAME = "FAKE";

	static final String MODEL = "fake-1";

	/** Raise it when the fixed findings below change. */
	static final int PROMPT_VERSION = 1;

	/** An issue covers at most this many lines from the top of its file. */
	private static final int MAX_ISSUE_LINES = 2;

	private static final List<IssueTemplate> ISSUES = List.of(
			new IssueTemplate(FindingCategory.SECURITY, Severity.CRITICAL, "Hardcoded credential",
					"A secret in source code is readable by everyone who can read the repository.",
					"Read the value from an environment variable.",
					"String apiKey = System.getenv(\"API_KEY\");"),
			new IssueTemplate(FindingCategory.PERFORMANCE, Severity.HIGH, "Query inside a loop",
					"One query per element makes the running time grow with the size of the input.",
					"Load all rows with a single query before the loop.", null),
			new IssueTemplate(FindingCategory.MAINTAINABILITY, Severity.MEDIUM, "Function does too much",
					"Several responsibilities in one function make it hard to test and to change.",
					"Split it into smaller functions, one per responsibility.", null),
			new IssueTemplate(FindingCategory.MAINTAINABILITY, Severity.LOW, "Unclear name",
					"A name that does not say what the value holds slows every reader down.",
					"Rename it after what it holds.", null));

	private static final RawFinding GOOD_PRACTICE = new RawFinding(FindingKind.GOOD_PRACTICE,
			FindingCategory.MAINTAINABILITY, null, null, null, null, "Consistent project structure",
			"Files are grouped in a way that makes related code easy to find.", null, null);

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public String model() {
		return MODEL;
	}

	@Override
	public int promptVersion() {
		return PROMPT_VERSION;
	}

	@Override
	public ProviderLimits limits() {
		return ProviderLimits.UNLIMITED;
	}

	@Override
	public List<RawFinding> review(ReviewChunk chunk) {
		List<RawFinding> findings = new ArrayList<>();
		List<SourceFile> files = chunk.files();
		for (int i = 0; i < files.size(); i++) {
			SourceFile file = files.get(i);
			int lines = file.lines().size();
			if (lines == 0) {
				continue;
			}
			findings.add(ISSUES.get(i % ISSUES.size()).at(file.path(), Math.min(MAX_ISSUE_LINES, lines)));
		}
		if (!files.isEmpty()) {
			findings.add(GOOD_PRACTICE);
		}
		return List.copyOf(findings);
	}

	private record IssueTemplate(FindingCategory category, Severity severity, String title, String explanation,
			String suggestion, String suggestedCode) {

		RawFinding at(String filePath, int lineEnd) {
			return new RawFinding(FindingKind.ISSUE, category, severity, filePath, 1, lineEnd, title, explanation,
					suggestion, suggestedCode);
		}

	}

}
