package com.codereviewer.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.codereviewer.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class ReviewSchemaIntegrationTest {

	private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

	@Autowired
	private JdbcTemplate jdbcTemplate;

	// analyses

	@Test
	void analysisWithValidValuesIsAccepted() {
		long id = insert(new AnalysisRow());

		assertThat(jdbcTemplate.queryForObject("SELECT created_at IS NOT NULL FROM analyses WHERE id = ?",
				Boolean.class, id)).isTrue();
	}

	@Test
	void analysisHasNoFailedFilesUnlessItSaysSo() {
		long id = insert(new AnalysisRow());

		assertThat(jdbcTemplate.queryForObject("SELECT files_failed FROM analyses WHERE id = ?", Integer.class, id))
				.isZero();
	}

	@Test
	void analysisKeepsTheNumberOfLinesItsScoresWereComputedFrom() {
		AnalysisRow row = new AnalysisRow();
		row.linesAnalyzed = 0;

		long id = insert(row);

		assertThat(jdbcTemplate.queryForObject("SELECT lines_analyzed FROM analyses WHERE id = ?", Integer.class, id))
				.isZero();
	}

	@Test
	void databaseRejectsANegativeNumberOfFailedFiles() {
		long id = insert(new AnalysisRow());

		assertRejected(() -> jdbcTemplate.update("UPDATE analyses SET files_failed = -1 WHERE id = ?", id),
				"ck_analyses_files_failed");
	}

	@Test
	void databaseRejectsASecondAnalysisForTheSameCacheKey() {
		insert(new AnalysisRow());

		assertRejected(() -> insert(new AnalysisRow()), "uq_analyses_cache_key");
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("otherCacheKeys")
	void sameCommitIsAnalysedAgainWhen(String description, Consumer<AnalysisRow> change) {
		insert(new AnalysisRow());
		AnalysisRow other = new AnalysisRow();
		change.accept(other);

		insert(other);

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM analyses WHERE commit_sha = ?", Integer.class,
				SHA)).isEqualTo(2);
	}

	static Stream<Arguments> otherCacheKeys() {
		return Stream.of(
				Arguments.of("the prompt version changes", change((AnalysisRow row) -> row.promptVersion = 2)),
				Arguments.of("the model changes", change((AnalysisRow row) -> row.model = "fake-2")),
				Arguments.of("the provider changes", change((AnalysisRow row) -> row.provider = "GEMINI")));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidAnalyses")
	void databaseRejectsAnAnalysisWith(String description, Consumer<AnalysisRow> change, String constraint) {
		AnalysisRow row = new AnalysisRow();
		change.accept(row);

		assertRejected(() -> insert(row), constraint);
	}

	static Stream<Arguments> invalidAnalyses() {
		return Stream.of(
				Arguments.of("a score above 100", change((AnalysisRow row) -> row.overallScore = 101),
						"ck_analyses_scores"),
				Arguments.of("a negative score", change((AnalysisRow row) -> row.securityScore = -1),
						"ck_analyses_scores"),
				Arguments.of("an owner that is not lower-case", change((AnalysisRow row) -> row.owner = "Octocat"),
						"ck_analyses_repo_lowercase"),
				Arguments.of("an upper-case commit SHA", change((AnalysisRow row) -> row.sha = SHA.toUpperCase()),
						"ck_analyses_commit_sha"),
				Arguments.of("a short commit SHA", change((AnalysisRow row) -> row.sha = "0123abc"),
						"ck_analyses_commit_sha"),
				Arguments.of("a negative file count", change((AnalysisRow row) -> row.filesSkipped = -1),
						"ck_analyses_counts"),
				Arguments.of("a negative number of lines", change((AnalysisRow row) -> row.linesAnalyzed = -1),
						"ck_analyses_lines_analyzed"),
				// The column has no default: a row that does not say how much code it covers fails.
				Arguments.of("no number of lines", change((AnalysisRow row) -> row.linesAnalyzed = null),
						"null value in column \"lines_analyzed\""));
	}

	// findings

	@ParameterizedTest(name = "{0}")
	@MethodSource("validFindings")
	void databaseAcceptsAFindingThatIs(String description, Consumer<FindingRow> change) {
		FindingRow row = issue(insert(new AnalysisRow()));
		change.accept(row);

		insert(row);

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM findings WHERE analysis_id = ?", Integer.class,
				row.analysisId)).isEqualTo(1);
	}

	static Stream<Arguments> validFindings() {
		return Stream.of(
				Arguments.of("an issue with a severity and a location", change((FindingRow row) -> {
				})),
				Arguments.of("an issue on a single line", change((FindingRow row) -> row.lineEnd = row.lineStart)),
				Arguments.of("a good practice without a location",
						change((FindingRow row) -> row.goodPractice().withoutLocation())),
				Arguments.of("a good practice with a location", change(FindingRow::goodPractice)),
				Arguments.of("a good practice for a whole file", change((FindingRow row) -> {
					row.goodPractice();
					row.lineStart = null;
					row.lineEnd = null;
				})));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidFindings")
	void databaseRejectsAFindingThatIs(String description, Consumer<FindingRow> change, String constraint) {
		FindingRow row = issue(insert(new AnalysisRow()));
		change.accept(row);

		assertRejected(() -> insert(row), constraint);
	}

	static Stream<Arguments> invalidFindings() {
		return Stream.of(
				Arguments.of("an issue without a severity", change((FindingRow row) -> row.severity = null),
						"ck_findings_kind_shape"),
				Arguments.of("an issue without a file", change((FindingRow row) -> row.filePath = null),
						"ck_findings_kind_shape"),
				Arguments.of("an issue without lines", change((FindingRow row) -> {
					row.lineStart = null;
					row.lineEnd = null;
				}), "ck_findings_kind_shape"),
				Arguments.of("an issue without an end line", change((FindingRow row) -> row.lineEnd = null),
						"ck_findings_kind_shape"),
				Arguments.of("a good practice with a severity", change((FindingRow row) -> row.kind = "GOOD_PRACTICE"),
						"ck_findings_kind_shape"),
				Arguments.of("a good practice with lines but no file",
						change((FindingRow row) -> row.goodPractice().filePath = null), "ck_findings_lines"),
				Arguments.of("a good practice with only a start line",
						change((FindingRow row) -> row.goodPractice().lineEnd = null), "ck_findings_lines"),
				Arguments.of("on line 0", change((FindingRow row) -> row.lineStart = 0), "ck_findings_lines"),
				Arguments.of("ending before it starts", change((FindingRow row) -> row.lineEnd = row.lineStart - 1),
						"ck_findings_lines"),
				Arguments.of("of an unknown kind", change((FindingRow row) -> row.kind = "PRAISE"), "ck_findings_kind"),
				Arguments.of("of an unknown category", change((FindingRow row) -> row.category = "STYLE"),
						"ck_findings_category"),
				Arguments.of("of an unknown severity", change((FindingRow row) -> row.severity = "INFO"),
						"ck_findings_severity"),
				Arguments.of("carrying a snippet without its first line number",
						change((FindingRow row) -> row.codeSnippet = "int x = 1;"), "ck_findings_snippet"),
				Arguments.of("attached to no existing analysis", change((FindingRow row) -> row.analysisId = -1L),
						"fk_findings_analysis"));
	}

	@Test
	void deletingAnAnalysisDeletesItsFindings() {
		long analysisId = insert(new AnalysisRow());
		insert(issue(analysisId));
		insert(issue(analysisId).goodPractice().withoutLocation());

		jdbcTemplate.update("DELETE FROM analyses WHERE id = ?", analysisId);

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM findings WHERE analysis_id = ?", Integer.class,
				analysisId)).isZero();
	}

	// reviews

	@Test
	void newReviewGetsTheDefaultsOfAJobThatHasNotStarted() {
		ReviewRow row = queuedReview();

		insert(row);

		Map<String, Object> stored = jdbcTemplate.queryForMap("SELECT * FROM reviews WHERE id = ?", row.id);
		assertThat(stored).containsEntry("chunks_done", 0)
				.containsEntry("chunks_total", 0)
				.containsEntry("attempts", 0)
				.containsEntry("cache_hit", false);
		assertThat(stored.get("run_after")).isNotNull();
		assertThat(stored.get("created_at")).isNotNull();
		assertThat(stored.get("commit_sha")).isNull();
		assertThat(stored.get("started_at")).isNull();
	}

	@Test
	void completedReviewPointsToItsAnalysis() {
		ReviewRow row = queuedReview();
		row.status = "COMPLETED";
		row.sha = SHA;
		row.analysisId = insert(new AnalysisRow());

		insert(row);

		assertThat(jdbcTemplate.queryForObject("SELECT analysis_id FROM reviews WHERE id = ?", Long.class, row.id))
				.isEqualTo(row.analysisId);
	}

	@Test
	void severalReviewsShareOneAnalysis() {
		long analysisId = insert(new AnalysisRow());
		for (int i = 0; i < 2; i++) {
			ReviewRow row = queuedReview();
			row.status = "COMPLETED";
			row.analysisId = analysisId;
			insert(row);
		}

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM reviews WHERE analysis_id = ?", Integer.class,
				analysisId)).isEqualTo(2);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidReviews")
	void databaseRejectsAReviewThatIs(String description, Consumer<ReviewRow> change, String constraint) {
		ReviewRow row = queuedReview();
		change.accept(row);

		assertRejected(() -> insert(row), constraint);
	}

	static Stream<Arguments> invalidReviews() {
		return Stream.of(
				Arguments.of("in an unknown status", change((ReviewRow row) -> row.status = "DONE"),
						"ck_reviews_status"),
				Arguments.of("in an unknown stage", change((ReviewRow row) -> row.stage = "DOWNLOADING"),
						"ck_reviews_stage"),
				Arguments.of("completed without an analysis", change((ReviewRow row) -> row.status = "COMPLETED"),
						"ck_reviews_completed_has_analysis"),
				Arguments.of("failed without an error code", change((ReviewRow row) -> row.status = "FAILED"),
						"ck_reviews_failed_has_error"),
				Arguments.of("owned by no existing user", change((ReviewRow row) -> row.userId = -1L),
						"fk_reviews_user"),
				Arguments.of("completed with an analysis that does not exist", change((ReviewRow row) -> {
					row.status = "COMPLETED";
					row.analysisId = -1L;
				}), "fk_reviews_analysis"),
				Arguments.of("for a repository name that is not lower-case",
						change((ReviewRow row) -> row.name = "Hello-World"), "ck_reviews_repo_lowercase"),
				Arguments.of("for an upper-case commit SHA", change((ReviewRow row) -> row.sha = SHA.toUpperCase()),
						"ck_reviews_commit_sha"),
				Arguments.of("further along than its total", change((ReviewRow row) -> {
					row.chunksDone = 3;
					row.chunksTotal = 2;
				}), "ck_reviews_progress"),
				Arguments.of("at a negative attempt", change((ReviewRow row) -> row.attempts = -1),
						"ck_reviews_attempts"));
	}

	@Test
	void databaseRejectsAnAnalysisOnAReviewThatIsNotCompleted() {
		ReviewRow row = queuedReview();
		row.analysisId = insert(new AnalysisRow());

		assertRejected(() -> insert(row), "ck_reviews_completed_has_analysis");
	}

	@Test
	void failedReviewKeepsItsErrorCode() {
		ReviewRow row = queuedReview();
		row.status = "FAILED";
		row.errorCode = "REPO_NOT_FOUND";

		insert(row);

		assertThat(jdbcTemplate.queryForObject("SELECT error_code FROM reviews WHERE id = ?", String.class, row.id))
				.isEqualTo("REPO_NOT_FOUND");
	}

	@Test
	void databaseRefusesToDeleteAnAnalysisThatAReviewPointsTo() {
		ReviewRow row = queuedReview();
		row.status = "COMPLETED";
		row.analysisId = insert(new AnalysisRow());
		insert(row);

		assertRejected(() -> jdbcTemplate.update("DELETE FROM analyses WHERE id = ?", row.analysisId),
				"fk_reviews_analysis");
	}

	// helpers

	private void assertRejected(Runnable statement, String constraint) {
		assertThatThrownBy(statement::run).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining(constraint);
	}

	// Gives the lambda a target type inside Arguments.of, which takes plain objects.
	private static <T> Consumer<T> change(Consumer<T> change) {
		return change;
	}

	private long insertUser() {
		return jdbcTemplate.queryForObject("INSERT INTO users (email, password_hash) VALUES (?, 'hash') RETURNING id",
				Long.class, "review-schema-" + UUID.randomUUID() + "@example.com");
	}

	private long insert(AnalysisRow row) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO analyses (repo_owner, repo_name, commit_sha, provider, model, prompt_version,
						overall_score, security_score, maintainability_score, performance_score,
						files_analyzed, files_skipped, lines_analyzed, findings_discarded)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, 80, 80, 10, ?, ?, 0)
				RETURNING id""", Long.class, row.owner, row.name, row.sha, row.provider, row.model,
				row.promptVersion, row.overallScore, row.securityScore, row.filesSkipped, row.linesAnalyzed);
	}

	private void insert(FindingRow row) {
		jdbcTemplate.update("""
				INSERT INTO findings (analysis_id, kind, category, severity, file_path, line_start, line_end,
						title, code_snippet)
				VALUES (?, ?, ?, ?, ?, ?, ?, 'Title', ?)""", row.analysisId, row.kind, row.category, row.severity,
				row.filePath, row.lineStart, row.lineEnd, row.codeSnippet);
	}

	private void insert(ReviewRow row) {
		jdbcTemplate.update("""
				INSERT INTO reviews (id, user_id, repo_owner, repo_name, commit_sha, status, stage, chunks_done,
						chunks_total, attempts, error_code, analysis_id)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", row.id, row.userId, row.owner, row.name, row.sha,
				row.status, row.stage, row.chunksDone, row.chunksTotal, row.attempts, row.errorCode,
				row.analysisId);
	}

	private static FindingRow issue(long analysisId) {
		FindingRow row = new FindingRow();
		row.analysisId = analysisId;
		return row;
	}

	private ReviewRow queuedReview() {
		ReviewRow row = new ReviewRow();
		row.userId = insertUser();
		return row;
	}

	/** A valid analysis; a test changes the one value it is about. */
	private static final class AnalysisRow {

		String owner = "octocat";
		String name = "hello-world";
		String sha = SHA;
		String provider = "FAKE";
		String model = "fake-1";
		int promptVersion = 1;
		int overallScore = 80;
		int securityScore = 80;
		int filesSkipped = 2;
		Integer linesAnalyzed = 1200;

	}

	/** A valid issue; a test changes the one value it is about. */
	private static final class FindingRow {

		Long analysisId;
		String kind = "ISSUE";
		String category = "SECURITY";
		String severity = "HIGH";
		String filePath = "src/Main.java";
		Integer lineStart = 3;
		Integer lineEnd = 5;
		String codeSnippet;

		FindingRow goodPractice() {
			kind = "GOOD_PRACTICE";
			severity = null;
			return this;
		}

		FindingRow withoutLocation() {
			filePath = null;
			lineStart = null;
			lineEnd = null;
			return this;
		}

	}

	/** A valid queued review; a test changes the one value it is about. */
	private static final class ReviewRow {

		final UUID id = UUID.randomUUID();
		Long userId;
		String owner = "octocat";
		String name = "hello-world";
		String sha;
		String status = "QUEUED";
		String stage;
		int chunksDone;
		int chunksTotal;
		int attempts;
		String errorCode;
		Long analysisId;

	}

}
