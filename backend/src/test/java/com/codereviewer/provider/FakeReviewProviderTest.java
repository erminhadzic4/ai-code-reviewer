package com.codereviewer.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.codereviewer.github.SourceFile;
import org.junit.jupiter.api.Test;

class FakeReviewProviderTest {

	private final FakeReviewProvider provider = new FakeReviewProvider();

	@Test
	void sameChunkAlwaysGivesTheSameFindings() {
		ReviewChunk chunk = chunkOf(6);

		List<RawFinding> first = provider.review(chunk);

		assertThat(first).isNotEmpty();
		assertThat(provider.review(chunk)).isEqualTo(first);
		assertThat(new FakeReviewProvider().review(chunkOf(6))).isEqualTo(first);
	}

	@Test
	void findingsContainIssuesAndAGoodPractice() {
		List<RawFinding> findings = provider.review(chunkOf(4));

		assertThat(findings).filteredOn(finding -> finding.kind() == FindingKind.ISSUE).hasSize(4);
		assertThat(findings).filteredOn(finding -> finding.kind() == FindingKind.GOOD_PRACTICE).hasSize(1);
	}

	@Test
	void fourFilesCoverEverySeverityAndEveryCategory() {
		List<RawFinding> issues = issuesOf(provider.review(chunkOf(4)));

		assertThat(issues).extracting(RawFinding::severity).containsExactly(Severity.values());
		assertThat(issues).extracting(RawFinding::category).contains(FindingCategory.values());
	}

	@Test
	void everyIssuePointsAtLinesThatExistInAFileOfTheChunk() {
		ReviewChunk chunk = new ReviewChunk(List.of(
				new SourceFile("src/One.java", "a"),
				new SourceFile("src/Two.java", "a\nb"),
				new SourceFile("src/Three.java", "a\nb\nc\n"),
				new SourceFile("src/Windows.cs", "a\r\nb\r\nc\r\n"),
				new SourceFile("src/Long.py", "x\n".repeat(500))));
		Map<String, Integer> lineCounts = chunk.files().stream()
				.collect(Collectors.toMap(SourceFile::path, file -> file.lines().size()));

		List<RawFinding> issues = issuesOf(provider.review(chunk));

		assertThat(issues).extracting(RawFinding::filePath).containsExactlyElementsOf(
				chunk.files().stream().map(SourceFile::path).toList());
		assertThat(issues).allSatisfy(issue -> {
			assertThat(issue.severity()).isNotNull();
			assertThat(issue.category()).isNotNull();
			assertThat(issue.lineStart()).isGreaterThanOrEqualTo(1);
			assertThat(issue.lineEnd()).isBetween(issue.lineStart(), lineCounts.get(issue.filePath()));
		});
	}

	@Test
	void fileWithOneLineGetsAnIssueOnThatLine() {
		RawFinding issue = issuesOf(provider.review(new ReviewChunk(List.of(new SourceFile("main.py", "pass")))))
				.getFirst();

		assertThat(issue.lineStart()).isEqualTo(1);
		assertThat(issue.lineEnd()).isEqualTo(1);
	}

	@Test
	void fileWithoutContentGetsNoIssue() {
		List<RawFinding> findings = provider.review(new ReviewChunk(List.of(
				new SourceFile("empty.py", ""), new SourceFile("main.py", "pass"))));

		assertThat(issuesOf(findings)).extracting(RawFinding::filePath).containsExactly("main.py");
	}

	@Test
	void goodPracticeHasNoSeverity() {
		List<RawFinding> goodPractices = provider.review(chunkOf(3)).stream()
				.filter(finding -> finding.kind() == FindingKind.GOOD_PRACTICE)
				.toList();

		assertThat(goodPractices).isNotEmpty().allSatisfy(finding -> {
			assertThat(finding.severity()).isNull();
			assertThat(finding.category()).isNotNull();
		});
	}

	@Test
	void everyFindingHasATitleThatFitsTheColumn() {
		assertThat(provider.review(chunkOf(8))).allSatisfy(
				finding -> assertThat(finding.title()).isNotBlank().hasSizeLessThanOrEqualTo(200));
	}

	@Test
	void emptyChunkGivesNoFindings() {
		assertThat(provider.review(new ReviewChunk(List.of()))).isEmpty();
	}

	@Test
	void nameModelAndPromptVersionIdentifyTheFake() {
		assertThat(provider.name()).isEqualTo("FAKE");
		assertThat(provider.model()).isEqualTo("fake-1");
		assertThat(provider.promptVersion()).isEqualTo(1);
	}

	private static List<RawFinding> issuesOf(List<RawFinding> findings) {
		return findings.stream().filter(finding -> finding.kind() == FindingKind.ISSUE).toList();
	}

	private static ReviewChunk chunkOf(int files) {
		return new ReviewChunk(IntStream.range(0, files)
				.mapToObj(i -> new SourceFile("src/File" + i + ".java", "line one\nline two\nline three\n"))
				.toList());
	}

}
