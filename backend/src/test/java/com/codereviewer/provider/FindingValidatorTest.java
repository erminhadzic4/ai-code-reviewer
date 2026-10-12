package com.codereviewer.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.codereviewer.github.SourceFile;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class FindingValidatorTest {

	private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();

	/** Ten lines: "line 1" to "line 10". */
	private static final SourceFile MAIN = new SourceFile("src/Main.java", numberedLines(10, "\n"));

	private static final SourceFile ONE_LINE = new SourceFile("one.py", "pass");

	private static final ReviewChunk CHUNK = new ReviewChunk(List.of(MAIN, ONE_LINE));

	private final FindingValidator validator = new FindingValidator(FACTORY.getValidator());

	@AfterAll
	static void closeFactory() {
		FACTORY.close();
	}

	@Test
	void wellFormedIssueOnExistingLinesIsAccepted() {
		RawFinding issue = issue();

		ValidationResult result = validator.validate(CHUNK, List.of(issue));

		assertThat(result.discarded()).isZero();
		assertThat(result.accepted()).singleElement().satisfies(accepted -> {
			assertThat(accepted.finding()).isSameAs(issue);
			assertThat(accepted.codeSnippet()).isEqualTo("line 2\nline 3\nline 4\nline 5");
			assertThat(accepted.snippetStartLine()).isEqualTo(2);
		});
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("acceptedFindings")
	void findingIsAcceptedWhenItIs(String description, RawFinding finding) {
		ValidationResult result = validator.validate(CHUNK, List.of(finding));

		assertThat(result.accepted()).extracting(VerifiedFinding::finding).containsExactly(finding);
		assertThat(result.discarded()).isZero();
	}

	static Stream<Arguments> acceptedFindings() {
		return Stream.of(
				Arguments.of("an issue on the first line", lines(issue(), 1, 1)),
				Arguments.of("an issue on the last line", lines(issue(), 10, 10)),
				Arguments.of("an issue over the whole file", lines(issue(), 1, 10)),
				Arguments.of("an issue without explanation, suggestion or code",
						new RawFinding(FindingKind.ISSUE, FindingCategory.SECURITY, Severity.LOW, MAIN.path(), 3, 3,
								"Title", null, null, null)),
				Arguments.of("an issue with texts of the greatest allowed length",
						new RawFinding(FindingKind.ISSUE, FindingCategory.SECURITY, Severity.LOW, MAIN.path(), 3, 3,
								"t".repeat(200), "e".repeat(800), "s".repeat(800), "c".repeat(2400))),
				Arguments.of("a good practice about the whole repository", goodPractice(null, null, null)),
				Arguments.of("a good practice about a file", goodPractice(MAIN.path(), null, null)),
				Arguments.of("a good practice about lines of a file", goodPractice(MAIN.path(), 4, 6)));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("discardedFindings")
	void findingIsDiscardedWhenItIs(String description, RawFinding finding) {
		ValidationResult result = validator.validate(CHUNK, List.of(finding));

		assertThat(result.accepted()).isEmpty();
		assertThat(result.discarded()).isEqualTo(1);
	}

	static Stream<Arguments> discardedFindings() {
		RawFinding issue = issue();
		return Stream.of(
				// single fields
				Arguments.of("without a kind", with(issue, f -> copy(f, null, f.category(), f.severity()))),
				Arguments.of("without a category", with(issue, f -> copy(f, f.kind(), null, f.severity()))),
				Arguments.of("without a title", title(issue, null)),
				Arguments.of("with a blank title", title(issue, "   ")),
				Arguments.of("with a title longer than its column", title(issue, "t".repeat(201))),
				Arguments.of("with an explanation that is too long",
						new RawFinding(FindingKind.ISSUE, FindingCategory.SECURITY, Severity.LOW, MAIN.path(), 3, 3,
								"Title", "e".repeat(801), null, null)),
				Arguments.of("with a suggestion that is too long",
						new RawFinding(FindingKind.ISSUE, FindingCategory.SECURITY, Severity.LOW, MAIN.path(), 3, 3,
								"Title", null, "s".repeat(801), null)),
				Arguments.of("with suggested code that is too long",
						new RawFinding(FindingKind.ISSUE, FindingCategory.SECURITY, Severity.LOW, MAIN.path(), 3, 3,
								"Title", null, null, "c".repeat(2401))),
				Arguments.of("with a path longer than its column", path(goodPractice(null, null, null),
						"a".repeat(1025))),
				// the shape of each kind
				Arguments.of("an issue without a severity", with(issue, f -> copy(f, f.kind(), f.category(), null))),
				Arguments.of("an issue without a file", path(issue, null)),
				Arguments.of("an issue without lines", lines(issue, null, null)),
				Arguments.of("an issue without a start line", lines(issue, null, 5)),
				Arguments.of("an issue without an end line", lines(issue, 3, null)),
				Arguments.of("a good practice with a severity",
						with(goodPractice(null, null, null),
								f -> copy(f, f.kind(), f.category(), Severity.LOW))),
				// location
				Arguments.of("in a file that was not sent", path(issue, "src/Other.java")),
				Arguments.of("in a path that differs only by ./", path(issue, "./src/Main.java")),
				Arguments.of("in a path that differs only in case", path(issue, "src/main.java")),
				Arguments.of("in a path with a leading slash", path(issue, "/src/Main.java")),
				Arguments.of("in an empty path", path(issue, "")),
				Arguments.of("on line 0", lines(issue, 0, 2)),
				Arguments.of("on a negative line", lines(issue, -1, 2)),
				Arguments.of("ending before it starts", lines(issue, 5, 4)),
				Arguments.of("ending after the last line", lines(issue, 9, 11)),
				Arguments.of("starting after the last line", lines(issue, 11, 11)),
				Arguments.of("a good practice with lines but no file", goodPractice(null, 3, 4)),
				Arguments.of("a good practice with only a start line", goodPractice(MAIN.path(), 3, null)),
				Arguments.of("a good practice with only an end line", goodPractice(MAIN.path(), null, 3)));
	}

	@Test
	void nullInTheListIsDiscardedLikeAnyOtherBrokenFinding() {
		List<RawFinding> reported = new ArrayList<>();
		reported.add(null);
		reported.add(issue());

		ValidationResult result = validator.validate(CHUNK, reported);

		assertThat(result.accepted()).hasSize(1);
		assertThat(result.discarded()).isEqualTo(1);
	}

	@Test
	void findingThatSpansMoreLinesThanTheLimitIsDiscarded() {
		SourceFile big = new SourceFile("big.py", numberedLines(300, "\n"));
		ReviewChunk chunk = new ReviewChunk(List.of(big));

		ValidationResult atLimit = validator.validate(chunk, List.of(lines(path(issue(), "big.py"), 1, 100)));
		ValidationResult overLimit = validator.validate(chunk, List.of(lines(path(issue(), "big.py"), 1, 101)));

		assertThat(atLimit.accepted()).hasSize(1);
		assertThat(overLimit.accepted()).isEmpty();
		assertThat(overLimit.discarded()).isEqualTo(1);
	}

	@ParameterizedTest(name = "lines {0}..{1} give a snippet from line {2}: {3}")
	@CsvSource({
			"1, 1, 1, 'line 1|line 2'",
			"1, 3, 1, 'line 1|line 2|line 3|line 4'",
			"5, 5, 4, 'line 4|line 5|line 6'",
			"10, 10, 9, 'line 9|line 10'",
			"1, 10, 1, 'line 1|line 2|line 3|line 4|line 5|line 6|line 7|line 8|line 9|line 10'" })
	void snippetIsTheCoveredLinesWithOneLineOfContextInsideTheFile(int start, int end, int snippetStart,
			String snippet) {
		VerifiedFinding accepted = validator.validate(CHUNK, List.of(lines(issue(), start, end))).accepted().getFirst();

		assertThat(accepted.snippetStartLine()).isEqualTo(snippetStart);
		assertThat(accepted.codeSnippet()).isEqualTo(snippet.replace('|', '\n'));
	}

	@Test
	void fileOfOneLineGivesASnippetOfThatLine() {
		VerifiedFinding accepted = validator
				.validate(CHUNK, List.of(lines(path(issue(), ONE_LINE.path()), 1, 1)))
				.accepted()
				.getFirst();

		assertThat(accepted.codeSnippet()).isEqualTo("pass");
		assertThat(accepted.snippetStartLine()).isEqualTo(1);
	}

	@Test
	void snippetComesFromTheFileAndNotFromTheProvider() {
		RawFinding lying = new RawFinding(FindingKind.ISSUE, FindingCategory.SECURITY, Severity.HIGH, MAIN.path(), 3,
				3, "Title", "The code says: rm -rf /", null, "rm -rf /");

		VerifiedFinding accepted = validator.validate(CHUNK, List.of(lying)).accepted().getFirst();

		assertThat(accepted.codeSnippet()).isEqualTo("line 2\nline 3\nline 4");
	}

	@Test
	void findingWithoutLinesHasNoSnippet() {
		VerifiedFinding accepted = validator.validate(CHUNK, List.of(goodPractice(MAIN.path(), null, null)))
				.accepted()
				.getFirst();

		assertThat(accepted.codeSnippet()).isNull();
		assertThat(accepted.snippetStartLine()).isNull();
	}

	@Test
	void linesOfAWindowsFileAreCountedLikeAnyOther() {
		SourceFile windows = new SourceFile("win.cs", numberedLines(3, "\r\n"));
		ReviewChunk chunk = new ReviewChunk(List.of(windows));

		ValidationResult lastLine = validator.validate(chunk, List.of(lines(path(issue(), "win.cs"), 3, 3)));
		ValidationResult pastTheEnd = validator.validate(chunk, List.of(lines(path(issue(), "win.cs"), 4, 4)));

		assertThat(lastLine.accepted()).singleElement()
				.satisfies(accepted -> assertThat(accepted.codeSnippet()).isEqualTo("line 2\nline 3"));
		assertThat(pastTheEnd.accepted()).isEmpty();
	}

	@Test
	void acceptedFindingsKeepTheirOrderAndTheRestAreCounted() {
		RawFinding first = lines(issue(), 1, 1);
		RawFinding broken = lines(issue(), 50, 60);
		RawFinding second = goodPractice(null, null, null);
		RawFinding alsoBroken = path(issue(), "missing.py");
		RawFinding third = lines(issue(), 10, 10);

		ValidationResult result = validator.validate(CHUNK, List.of(first, broken, second, alsoBroken, third));

		assertThat(result.accepted()).extracting(VerifiedFinding::finding).containsExactly(first, second, third);
		assertThat(result.discarded()).isEqualTo(2);
	}

	@Test
	void noFindingsGiveAnEmptyResult() {
		ValidationResult result = validator.validate(CHUNK, List.of());

		assertThat(result.accepted()).isEmpty();
		assertThat(result.discarded()).isZero();
	}

	@Test
	void everyFindingOfTheFakeProviderIsAccepted() {
		ReviewChunk chunk = new ReviewChunk(List.of(MAIN, ONE_LINE, new SourceFile("a.go", "a\nb\n"),
				new SourceFile("b.rs", "a\r\nb\r\nc"), new SourceFile("c.ts", "x")));
		List<RawFinding> reported = new FakeReviewProvider().review(chunk);

		ValidationResult result = validator.validate(chunk, reported);

		assertThat(result.discarded()).isZero();
		assertThat(result.accepted()).hasSameSizeAs(reported);
	}

	private static RawFinding issue() {
		return new RawFinding(FindingKind.ISSUE, FindingCategory.SECURITY, Severity.HIGH, MAIN.path(), 3, 4,
				"Hardcoded credential", "Why it matters.", "How to fix it.", "String key = System.getenv(\"KEY\");");
	}

	private static RawFinding goodPractice(String filePath, Integer lineStart, Integer lineEnd) {
		return new RawFinding(FindingKind.GOOD_PRACTICE, FindingCategory.MAINTAINABILITY, null, filePath, lineStart,
				lineEnd, "Consistent structure", "Why it is good.", null, null);
	}

	private static RawFinding with(RawFinding finding, UnaryOperator<RawFinding> change) {
		return change.apply(finding);
	}

	private static RawFinding copy(RawFinding f, FindingKind kind, FindingCategory category, Severity severity) {
		return new RawFinding(kind, category, severity, f.filePath(), f.lineStart(), f.lineEnd(), f.title(),
				f.explanation(), f.suggestion(), f.suggestedCode());
	}

	private static RawFinding lines(RawFinding f, Integer lineStart, Integer lineEnd) {
		return new RawFinding(f.kind(), f.category(), f.severity(), f.filePath(), lineStart, lineEnd, f.title(),
				f.explanation(), f.suggestion(), f.suggestedCode());
	}

	private static RawFinding path(RawFinding f, String filePath) {
		return new RawFinding(f.kind(), f.category(), f.severity(), filePath, f.lineStart(), f.lineEnd(), f.title(),
				f.explanation(), f.suggestion(), f.suggestedCode());
	}

	private static RawFinding title(RawFinding f, String title) {
		return new RawFinding(f.kind(), f.category(), f.severity(), f.filePath(), f.lineStart(), f.lineEnd(), title,
				f.explanation(), f.suggestion(), f.suggestedCode());
	}

	private static String numberedLines(int count, String lineBreak) {
		return String.join(lineBreak, IntStream.rangeClosed(1, count).mapToObj(i -> "line " + i).toList())
				+ lineBreak;
	}

}
