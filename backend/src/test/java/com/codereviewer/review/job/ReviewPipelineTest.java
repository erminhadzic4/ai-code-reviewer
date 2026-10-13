package com.codereviewer.review.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.codereviewer.provider.FindingCategory;
import com.codereviewer.provider.FindingKind;
import com.codereviewer.provider.RawFinding;
import com.codereviewer.provider.Severity;
import com.codereviewer.provider.VerifiedFinding;
import org.junit.jupiter.api.Test;

/** The one rule of the pipeline that needs no repository: a good practice counts once. */
class ReviewPipelineTest {

	@Test
	void sameGoodPracticeFromSeveralChunksIsKeptOnce() {
		VerifiedFinding first = goodPractice("Consistent project structure", "a.py");
		VerifiedFinding second = goodPractice("Consistent project structure", "b.py");
		VerifiedFinding third = goodPractice("Consistent project structure", null);

		assertThat(ReviewPipeline.oneGoodPracticePerTitle(List.of(first, second, third))).containsExactly(first);
	}

	@Test
	void titlesThatDifferOnlyInCaseOrSurroundingSpaceAreTheSame() {
		VerifiedFinding first = goodPractice("Clear naming", null);

		assertThat(ReviewPipeline.oneGoodPracticePerTitle(List.of(first, goodPractice("clear NAMING", null),
				goodPractice("  Clear naming ", null)))).containsExactly(first);
	}

	@Test
	void goodPracticesWithDifferentTitlesAreAllKept() {
		List<VerifiedFinding> findings = List.of(goodPractice("Clear naming", null),
				goodPractice("Clear names", null), goodPractice("Small functions", null));

		assertThat(ReviewPipeline.oneGoodPracticePerTitle(findings)).isEqualTo(findings);
	}

	@Test
	void issuesWithTheSameTitleAreAllKept() {
		List<VerifiedFinding> findings = List.of(issue("Hardcoded credential", "a.py"),
				issue("Hardcoded credential", "b.py"), issue("Hardcoded credential", "a.py"));

		assertThat(ReviewPipeline.oneGoodPracticePerTitle(findings)).isEqualTo(findings);
	}

	@Test
	void issueDoesNotStandInForAGoodPracticeOfTheSameTitle() {
		List<VerifiedFinding> findings = List.of(issue("Error handling", "a.py"),
				goodPractice("Error handling", null));

		assertThat(ReviewPipeline.oneGoodPracticePerTitle(findings)).isEqualTo(findings);
	}

	@Test
	void orderOfTheFindingsIsKept() {
		VerifiedFinding issueA = issue("Query inside a loop", "a.py");
		VerifiedFinding praise = goodPractice("Clear naming", null);
		VerifiedFinding issueB = issue("Unclear name", "b.py");
		VerifiedFinding other = goodPractice("Small functions", null);

		assertThat(ReviewPipeline.oneGoodPracticePerTitle(
				List.of(issueA, praise, issueB, goodPractice("Clear naming", "b.py"), other)))
				.containsExactly(issueA, praise, issueB, other);
	}

	@Test
	void noFindingsStayNoFindings() {
		assertThat(ReviewPipeline.oneGoodPracticePerTitle(List.of())).isEmpty();
	}

	private static VerifiedFinding goodPractice(String title, String path) {
		return new VerifiedFinding(new RawFinding(FindingKind.GOOD_PRACTICE, FindingCategory.MAINTAINABILITY, null,
				path, null, null, title, "Why it is good.", null, null), null, null);
	}

	private static VerifiedFinding issue(String title, String path) {
		return new VerifiedFinding(new RawFinding(FindingKind.ISSUE, FindingCategory.SECURITY, Severity.HIGH, path, 1,
				1, title, "Why it matters.", "How to fix it.", null), "line", 1);
	}

}
