package com.codereviewer.review.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import com.codereviewer.github.SourceFile;
import com.codereviewer.provider.CodeReviewProvider;
import com.codereviewer.provider.FindingCategory;
import com.codereviewer.provider.FindingKind;
import com.codereviewer.provider.FindingValidator;
import com.codereviewer.provider.ProviderErrorCode;
import com.codereviewer.provider.ProviderException;
import com.codereviewer.provider.ProviderLimits;
import com.codereviewer.provider.RawFinding;
import com.codereviewer.provider.ReviewChunk;
import com.codereviewer.provider.Severity;
import com.codereviewer.provider.VerifiedFinding;
import com.codereviewer.testsupport.MutableClock;
import com.codereviewer.testsupport.RecordingSleeper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ChunkAnalyzerTest {

	private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();

	// The test clock follows the real time, so a wait computed from it is a few milliseconds
	// short of the round number.
	private static final Duration TOLERANCE = Duration.ofSeconds(1);

	private static final SourceFile A = new SourceFile("a.py", "one\ntwo\nthree\n");
	private static final SourceFile B = new SourceFile("b.py", "one\ntwo\n");
	private static final SourceFile C = new SourceFile("c.py", "one\n");
	private static final SourceFile D = new SourceFile("d.py", "one\ntwo\nthree\nfour\n");

	private final MutableClock clock = new MutableClock();
	private final RecordingSleeper sleeper = new RecordingSleeper(clock);
	private final ScriptedProvider provider = new ScriptedProvider();
	private final List<String> progress = new ArrayList<>();

	@AfterAll
	static void closeFactory() {
		FACTORY.close();
	}

	// every chunk reviewed

	@Test
	void findingsOfEveryChunkAreCollectedInOrder() throws InterruptedException {
		provider.answers(List.of(issue(A), issue(B)), List.of(issue(C)));

		ChunkAnalysis analysis = analyze(chunk(A, B), chunk(C));

		assertThat(analysis.findings()).extracting(VerifiedFinding::finding)
				.extracting(RawFinding::filePath)
				.containsExactly("a.py", "b.py", "c.py");
		assertThat(analysis.findingsDiscarded()).isZero();
		assertThat(analysis.filesAnalyzed()).isEqualTo(3);
		assertThat(analysis.filesFailed()).isZero();
		assertThat(analysis.linesAnalyzed()).isEqualTo(6);
		assertThat(provider.received).containsExactly(chunk(A, B), chunk(C));
		assertThat(progress).containsExactly("1/2", "2/2");
		assertThat(sleeper.slept()).isEmpty();
	}

	@Test
	void acceptedFindingsCarryTheSnippetCutFromTheFile() throws InterruptedException {
		provider.answers(List.of(issue(A)));

		ChunkAnalysis analysis = analyze(chunk(A));

		assertThat(analysis.findings()).singleElement().satisfies(finding -> {
			assertThat(finding.codeSnippet()).isEqualTo("one\ntwo");
			assertThat(finding.snippetStartLine()).isEqualTo(1);
		});
	}

	@Test
	void discardedFindingsAreSummedOverTheChunks() throws InterruptedException {
		provider.answers(List.of(issue(A), issueIn("not-sent.py")), List.of(issueIn("other.py"), issueIn("b.py")));

		ChunkAnalysis analysis = analyze(chunk(A), chunk(C));

		assertThat(analysis.findings()).hasSize(1);
		assertThat(analysis.findingsDiscarded()).isEqualTo(3);
	}

	@Test
	void chunkWithoutFindingsStillCountsAsReviewed() throws InterruptedException {
		provider.answers(List.of());

		ChunkAnalysis analysis = analyze(chunk(A, B));

		assertThat(analysis.findings()).isEmpty();
		assertThat(analysis.filesAnalyzed()).isEqualTo(2);
		assertThat(analysis.linesAnalyzed()).isEqualTo(5);
	}

	@Test
	void noChunksGiveAnEmptyAnalysisWithoutARequest() throws InterruptedException {
		ChunkAnalysis analysis = analyze();

		assertThat(analysis).isEqualTo(new ChunkAnalysis(List.of(), 0, 0, 0, 0));
		assertThat(provider.received).isEmpty();
		assertThat(progress).isEmpty();
	}

	// failures that pass by themselves

	@ParameterizedTest
	@EnumSource(names = { "PROVIDER_RATE_LIMITED", "PROVIDER_UNAVAILABLE", "PROVIDER_INVALID_RESPONSE" })
	void sameChunkIsSentAgainAfterAPause(ProviderErrorCode code) throws InterruptedException {
		provider.answers(failure(code), failure(code), List.of(issue(A)));

		ChunkAnalysis analysis = analyze(chunk(A));

		assertThat(analysis.findings()).hasSize(1);
		assertThat(provider.received).containsExactly(chunk(A), chunk(A), chunk(A));
		assertThat(sleeper.slept()).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(20));
		assertThat(progress).containsExactly("1/1");
	}

	@Test
	void thirdFailureOfAChunkIsHandedToTheJob() {
		ProviderException third = failure(ProviderErrorCode.PROVIDER_UNAVAILABLE);
		provider.answers(List.of(issue(A)), failure(ProviderErrorCode.PROVIDER_RATE_LIMITED),
				failure(ProviderErrorCode.PROVIDER_UNAVAILABLE), third, List.of(issue(B)));

		assertThatThrownBy(() -> analyze(chunk(A), chunk(B))).isSameAs(third);

		assertThat(provider.received).containsExactly(chunk(A), chunk(B), chunk(B), chunk(B));
		assertThat(sleeper.slept()).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(20));
	}

	@Test
	void everyChunkGetsItsOwnThreeSendings() throws InterruptedException {
		provider.answers(failure(ProviderErrorCode.PROVIDER_UNAVAILABLE),
				failure(ProviderErrorCode.PROVIDER_UNAVAILABLE), List.of(),
				failure(ProviderErrorCode.PROVIDER_UNAVAILABLE), failure(ProviderErrorCode.PROVIDER_UNAVAILABLE),
				List.of());

		ChunkAnalysis analysis = analyze(chunk(A), chunk(B));

		assertThat(analysis.filesAnalyzed()).isEqualTo(2);
		assertThat(provider.received).hasSize(6);
	}

	@Test
	void pauseIsTheOneTheProviderAskedFor() throws InterruptedException {
		provider.answers(new ProviderException(ProviderErrorCode.PROVIDER_RATE_LIMITED, "limited",
				clock.instant().plusSeconds(30)), List.of());

		analyze(chunk(A));

		assertThat(sleeper.slept()).hasSize(1);
		assertThat(sleeper.total()).isCloseTo(Duration.ofSeconds(30), TOLERANCE);
	}

	@Test
	void chunkIsSentAgainAtOnceWhenTheTimeTheProviderGaveHasPassed() throws InterruptedException {
		provider.answers(new ProviderException(ProviderErrorCode.PROVIDER_RATE_LIMITED, "limited",
				clock.instant().minusSeconds(10)), List.of());

		analyze(chunk(A));

		assertThat(provider.received).hasSize(2);
		assertThat(sleeper.slept()).isEmpty();
	}

	@Test
	void waitLongerThanAMinuteIsHandedToTheJobInsteadOfSleeping() {
		Instant retryAt = clock.instant().plus(Duration.ofMinutes(5));
		ProviderException limited = new ProviderException(ProviderErrorCode.PROVIDER_RATE_LIMITED, "limited", retryAt);
		provider.answers(limited, List.of());

		assertThatThrownBy(() -> analyze(chunk(A))).isSameAs(limited);

		assertThat(provider.received).hasSize(1);
		assertThat(sleeper.slept()).isEmpty();
	}

	@Test
	void sendingAgainKeepsToTheLimitsOfTheProvider() throws InterruptedException {
		// Two requests a minute: the third sending of the chunk has to wait for the first one
		// to be a minute old, whatever the pauses between the sendings were.
		ChunkAnalyzer paced = analyzer(new ProviderLimits(2, 600_000));
		provider.answers(failure(ProviderErrorCode.PROVIDER_UNAVAILABLE),
				failure(ProviderErrorCode.PROVIDER_UNAVAILABLE), List.of());

		paced.analyze(List.of(chunk(A)), this::record);

		assertThat(provider.received).hasSize(3);
		assertThat(sleeper.total()).isCloseTo(Duration.ofSeconds(60), TOLERANCE);
	}

	@Test
	void interruptedPauseEndsTheAnalysis() {
		Sleeper interrupted = duration -> {
			throw new InterruptedException();
		};
		ChunkAnalyzer analyzer = new ChunkAnalyzer(provider, validator(),
				new RequestPacer(ProviderLimits.UNLIMITED, clock, interrupted), clock, interrupted);
		provider.answers(failure(ProviderErrorCode.PROVIDER_UNAVAILABLE), List.of());

		assertThatThrownBy(() -> analyzer.analyze(List.of(chunk(A)), this::record))
				.isInstanceOf(InterruptedException.class);

		assertThat(provider.received).hasSize(1);
	}

	// failures that end the analysis at once

	@ParameterizedTest
	@EnumSource(names = { "PROVIDER_QUOTA_EXHAUSTED", "PROVIDER_AUTH_FAILED", "PROVIDER_MISCONFIGURED" })
	void failureThatSendingAgainCannotFixEndsTheAnalysis(ProviderErrorCode code) {
		ProviderException failure = failure(code);
		provider.answers(List.of(issue(A)), failure, List.of(issue(C)));

		assertThatThrownBy(() -> analyze(chunk(A), chunk(B), chunk(C))).isSameAs(failure);

		assertThat(provider.received).containsExactly(chunk(A), chunk(B));
		assertThat(sleeper.slept()).isEmpty();
		assertThat(progress).containsExactly("1/3");
	}

	@Test
	void mistakeInTheProviderIsNotSwallowed() {
		provider.answers(new IllegalStateException("bug"));

		assertThatThrownBy(() -> analyze(chunk(A))).isInstanceOf(IllegalStateException.class).hasMessage("bug");
		assertThat(provider.received).hasSize(1);
	}

	// failures caused by the content

	@ParameterizedTest
	@EnumSource(names = { "PROVIDER_OUTPUT_TRUNCATED", "PROVIDER_BLOCKED" })
	void chunkWithSeveralFilesIsSentAgainAsTwoHalves(ProviderErrorCode code) throws InterruptedException {
		provider.answers(failure(code), List.of(issue(A), issue(B)), List.of(issue(C), issue(D)), List.of());

		ChunkAnalysis analysis = analyze(chunk(A, B, C, D), chunk(A));

		assertThat(provider.received).containsExactly(chunk(A, B, C, D), chunk(A, B), chunk(C, D), chunk(A));
		assertThat(analysis.findings()).hasSize(4);
		assertThat(analysis.filesAnalyzed()).isEqualTo(5);
		assertThat(analysis.filesFailed()).isZero();
		assertThat(analysis.linesAnalyzed()).isEqualTo(13);
		assertThat(progress).containsExactly("1/3", "2/3", "3/3");
		assertThat(sleeper.slept()).isEmpty();
	}

	@Test
	void oddNumberOfFilesIsSplitInOrderWithTheLargerHalfFirst() throws InterruptedException {
		provider.answers(failure(ProviderErrorCode.PROVIDER_OUTPUT_TRUNCATED), List.of(), List.of());

		analyze(chunk(A, B, C));

		assertThat(provider.received).containsExactly(chunk(A, B, C), chunk(A, B), chunk(C));
	}

	@Test
	void halfThatFailsAgainIsCountedAsFailedAndNotSplitFurther() throws InterruptedException {
		provider.answers(failure(ProviderErrorCode.PROVIDER_OUTPUT_TRUNCATED), List.of(issue(A)),
				failure(ProviderErrorCode.PROVIDER_BLOCKED), List.of(issue(A)));

		ChunkAnalysis analysis = analyze(chunk(A, B, C, D), chunk(A));

		assertThat(provider.received).containsExactly(chunk(A, B, C, D), chunk(A, B), chunk(C, D), chunk(A));
		assertThat(analysis.findings()).hasSize(2);
		assertThat(analysis.filesAnalyzed()).isEqualTo(3);
		assertThat(analysis.filesFailed()).isEqualTo(2);
		// a.py and b.py from the first half and a.py again from the second chunk; c.py and d.py
		// were never reviewed, so their lines are not part of what the scores are computed from.
		assertThat(analysis.linesAnalyzed()).isEqualTo(8);
		assertThat(progress).containsExactly("1/3", "2/3", "3/3");
	}

	@ParameterizedTest
	@EnumSource(names = { "PROVIDER_OUTPUT_TRUNCATED", "PROVIDER_BLOCKED" })
	void chunkWithOneFileIsCountedAsFailedWithoutAnotherRequest(ProviderErrorCode code) throws InterruptedException {
		provider.answers(failure(code), List.of(issue(B)));

		ChunkAnalysis analysis = analyze(chunk(A), chunk(B));

		assertThat(provider.received).containsExactly(chunk(A), chunk(B));
		assertThat(analysis.filesFailed()).isEqualTo(1);
		assertThat(analysis.filesAnalyzed()).isEqualTo(1);
		assertThat(analysis.linesAnalyzed()).isEqualTo(2);
		assertThat(analysis.findings()).extracting(finding -> finding.finding().filePath()).containsExactly("b.py");
		assertThat(progress).containsExactly("1/2", "2/2");
	}

	@Test
	void halfIsSentAgainWhenItsFailurePassesByItself() throws InterruptedException {
		provider.answers(failure(ProviderErrorCode.PROVIDER_BLOCKED), failure(ProviderErrorCode.PROVIDER_UNAVAILABLE),
				List.of(), List.of());

		ChunkAnalysis analysis = analyze(chunk(A, B));

		assertThat(provider.received).containsExactly(chunk(A, B), chunk(A), chunk(A), chunk(B));
		assertThat(analysis.filesAnalyzed()).isEqualTo(2);
		assertThat(sleeper.slept()).containsExactly(Duration.ofSeconds(5));
	}

	@Test
	void quotaThatRunsOutOnAHalfEndsTheAnalysis() {
		ProviderException quota = failure(ProviderErrorCode.PROVIDER_QUOTA_EXHAUSTED);
		provider.answers(failure(ProviderErrorCode.PROVIDER_OUTPUT_TRUNCATED), List.of(), quota);

		assertThatThrownBy(() -> analyze(chunk(A, B))).isSameAs(quota);
	}

	@Test
	void analysisFailsWithTheLastErrorWhenNoChunkCouldBeReviewed() {
		ProviderException last = failure(ProviderErrorCode.PROVIDER_BLOCKED);
		provider.answers(failure(ProviderErrorCode.PROVIDER_OUTPUT_TRUNCATED),
				failure(ProviderErrorCode.PROVIDER_OUTPUT_TRUNCATED), failure(ProviderErrorCode.PROVIDER_BLOCKED),
				last);

		assertThatThrownBy(() -> analyze(chunk(A, B), chunk(C))).isSameAs(last);

		assertThat(provider.received).containsExactly(chunk(A, B), chunk(A), chunk(B), chunk(C));
		assertThat(progress).containsExactly("1/3", "2/3", "3/3");
	}

	@Test
	void oneReviewedChunkIsEnoughForAPartialAnalysis() throws InterruptedException {
		provider.answers(failure(ProviderErrorCode.PROVIDER_BLOCKED), failure(ProviderErrorCode.PROVIDER_BLOCKED),
				List.of());

		ChunkAnalysis analysis = analyze(chunk(A), chunk(B), chunk(C));

		assertThat(analysis.filesFailed()).isEqualTo(2);
		assertThat(analysis.filesAnalyzed()).isEqualTo(1);
		assertThat(analysis.linesAnalyzed()).isEqualTo(1);
	}

	// helpers

	private ChunkAnalysis analyze(ReviewChunk... chunks) throws InterruptedException {
		return analyzer(ProviderLimits.UNLIMITED).analyze(Arrays.asList(chunks), this::record);
	}

	private ChunkAnalyzer analyzer(ProviderLimits limits) {
		return new ChunkAnalyzer(provider, validator(), new RequestPacer(limits, clock, sleeper), clock, sleeper);
	}

	private static FindingValidator validator() {
		return new FindingValidator(FACTORY.getValidator());
	}

	private void record(int done, int total) {
		progress.add(done + "/" + total);
	}

	private static ReviewChunk chunk(SourceFile... files) {
		return new ReviewChunk(List.of(files));
	}

	/** An issue on the first line of a file; valid when the file is part of the chunk. */
	private static RawFinding issue(SourceFile file) {
		return issueIn(file.path());
	}

	private static RawFinding issueIn(String path) {
		return new RawFinding(FindingKind.ISSUE, FindingCategory.SECURITY, Severity.HIGH, path, 1, 1, "Title",
				"Explanation", "Suggestion", null);
	}

	private static ProviderException failure(ProviderErrorCode code) {
		return new ProviderException(code, "Message for " + code);
	}

	/** Answers each call with the next scripted answer: a list of findings, or an exception to throw. */
	private static final class ScriptedProvider implements CodeReviewProvider {

		private final Deque<Object> script = new ArrayDeque<>();
		private final List<ReviewChunk> received = new ArrayList<>();

		void answers(Object... answers) {
			script.addAll(List.of(answers));
		}

		@Override
		public String name() {
			return "SCRIPTED";
		}

		@Override
		public String model() {
			return "scripted-1";
		}

		@Override
		public int promptVersion() {
			return 1;
		}

		@Override
		public ProviderLimits limits() {
			return ProviderLimits.UNLIMITED;
		}

		@Override
		@SuppressWarnings("unchecked")
		public List<RawFinding> review(ReviewChunk chunk) {
			received.add(chunk);
			Object answer = script.removeFirst();
			if (answer instanceof RuntimeException failure) {
				throw failure;
			}
			return (List<RawFinding>) answer;
		}

	}

}
