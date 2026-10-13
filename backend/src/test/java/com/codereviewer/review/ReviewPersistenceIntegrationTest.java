package com.codereviewer.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.codereviewer.IntegrationTest;
import com.codereviewer.github.RepoRef;
import com.codereviewer.github.SourceFile;
import com.codereviewer.provider.FakeReviewProvider;
import com.codereviewer.provider.FindingCategory;
import com.codereviewer.provider.FindingKind;
import com.codereviewer.provider.FindingValidator;
import com.codereviewer.provider.RawFinding;
import com.codereviewer.provider.ReviewChunk;
import com.codereviewer.provider.Severity;
import com.codereviewer.provider.ValidationResult;
import com.codereviewer.provider.VerifiedFinding;
import com.codereviewer.user.User;
import com.codereviewer.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** The entities written to and read from the real schema, which the migration defines. */
@IntegrationTest
@Transactional
class ReviewPersistenceIntegrationTest {

	private static final RepoRef REPO = new RepoRef("octocat", "hello-world");
	private static final String SHA = "89abcdef0123456789abcdef0123456789abcdef";

	@Autowired
	private ReviewRepository reviewRepository;

	@Autowired
	private AnalysisRepository analysisRepository;

	@Autowired
	private FindingRepository findingRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private FindingValidator findingValidator;

	@Test
	void newReviewIsStoredAsAQueuedJob() {
		long userId = savedUserId();

		UUID id = reviewRepository.saveAndFlush(new Review(userId, REPO)).getId();
		entityManager.clear();

		Review stored = reviewRepository.findById(id).orElseThrow();
		assertThat(stored.getId()).isEqualTo(id);
		assertThat(stored.getUserId()).isEqualTo(userId);
		assertThat(stored.getRepoOwner()).isEqualTo("octocat");
		assertThat(stored.getRepoName()).isEqualTo("hello-world");
		assertThat(stored.getStatus()).isEqualTo(ReviewStatus.QUEUED);
		assertThat(stored.getStage()).isNull();
		assertThat(stored.getAttempts()).isZero();
		assertThat(stored.getChunksDone()).isZero();
		assertThat(stored.getChunksTotal()).isZero();
		assertThat(stored.isCacheHit()).isFalse();
		assertThat(stored.getCreatedAt()).isNotNull();
		assertThat(stored.getRunAfter()).isEqualTo(stored.getCreatedAt());
		assertThat(stored.getBranch()).isNull();
		assertThat(stored.getCommitSha()).isNull();
		assertThat(stored.getAnalysis()).isNull();
		assertThat(stored.getErrorCode()).isNull();
		assertThat(stored.getStartedAt()).isNull();
		assertThat(stored.getFinishedAt()).isNull();
	}

	@Test
	void everyReviewGetsItsOwnId() {
		long userId = savedUserId();

		UUID first = reviewRepository.saveAndFlush(new Review(userId, REPO)).getId();
		UUID second = reviewRepository.saveAndFlush(new Review(userId, REPO)).getId();

		assertThat(first).isNotNull().isNotEqualTo(second);
	}

	@Test
	void analysisIsReadBackAsItWasStored() {
		Long id = analysisRepository.saveAndFlush(analysis()).getId();
		entityManager.clear();

		Analysis stored = analysisRepository.findById(id).orElseThrow();
		assertThat(stored.getRepoOwner()).isEqualTo("octocat");
		assertThat(stored.getRepoName()).isEqualTo("hello-world");
		assertThat(stored.getCommitSha()).isEqualTo(SHA);
		assertThat(stored.getProvider()).isEqualTo("FAKE");
		assertThat(stored.getModel()).isEqualTo("fake-1");
		assertThat(stored.getPromptVersion()).isEqualTo(1);
		assertThat(stored.getOverallScore()).isEqualTo(72);
		assertThat(stored.getSecurityScore()).isEqualTo(0);
		assertThat(stored.getMaintainabilityScore()).isEqualTo(100);
		assertThat(stored.getPerformanceScore()).isEqualTo(85);
		assertThat(stored.getFilesAnalyzed()).isEqualTo(12);
		assertThat(stored.getFilesSkipped()).isEqualTo(3);
		assertThat(stored.getFilesFailed()).isEqualTo(2);
		assertThat(stored.getLinesAnalyzed()).isEqualTo(1840);
		assertThat(stored.getFindingsDiscarded()).isEqualTo(1);
		assertThat(stored.getCreatedAt()).isNotNull();
	}

	@Test
	void issueAndGoodPracticeAreReadBackAsTheyWereStored() {
		Analysis analysis = analysisRepository.saveAndFlush(analysis());
		RawFinding issue = new RawFinding(FindingKind.ISSUE, FindingCategory.SECURITY, Severity.CRITICAL,
				"src/Main.java", 3, 4, "Hardcoded credential", "Why it matters.", "How to fix it.",
				"String key = System.getenv(\"KEY\");");
		RawFinding goodPractice = new RawFinding(FindingKind.GOOD_PRACTICE, FindingCategory.MAINTAINABILITY, null,
				null, null, null, "Consistent structure", "Why it is good.", null, null);
		Long issueId = findingRepository.saveAndFlush(new Finding(analysis, issue, "a\nb\nc\nd", 2)).getId();
		Long goodPracticeId = findingRepository.saveAndFlush(new Finding(analysis, goodPractice, null, null)).getId();
		entityManager.clear();

		Finding storedIssue = findingRepository.findById(issueId).orElseThrow();
		assertThat(storedIssue.getAnalysis().getId()).isEqualTo(analysis.getId());
		assertThat(storedIssue.getKind()).isEqualTo(FindingKind.ISSUE);
		assertThat(storedIssue.getCategory()).isEqualTo(FindingCategory.SECURITY);
		assertThat(storedIssue.getSeverity()).isEqualTo(Severity.CRITICAL);
		assertThat(storedIssue.getFilePath()).isEqualTo("src/Main.java");
		assertThat(storedIssue.getLineStart()).isEqualTo(3);
		assertThat(storedIssue.getLineEnd()).isEqualTo(4);
		assertThat(storedIssue.getTitle()).isEqualTo("Hardcoded credential");
		assertThat(storedIssue.getExplanation()).isEqualTo("Why it matters.");
		assertThat(storedIssue.getSuggestion()).isEqualTo("How to fix it.");
		assertThat(storedIssue.getSuggestedCode()).isEqualTo("String key = System.getenv(\"KEY\");");
		assertThat(storedIssue.getCodeSnippet()).isEqualTo("a\nb\nc\nd");
		assertThat(storedIssue.getSnippetStartLine()).isEqualTo(2);

		Finding storedGoodPractice = findingRepository.findById(goodPracticeId).orElseThrow();
		assertThat(storedGoodPractice.getKind()).isEqualTo(FindingKind.GOOD_PRACTICE);
		assertThat(storedGoodPractice.getSeverity()).isNull();
		assertThat(storedGoodPractice.getFilePath()).isNull();
		assertThat(storedGoodPractice.getLineStart()).isNull();
		assertThat(storedGoodPractice.getCodeSnippet()).isNull();
	}

	@Test
	void textColumnsHoldMoreThanAShortString() {
		Analysis analysis = analysisRepository.saveAndFlush(analysis());
		String longText = "x".repeat(20_000);
		RawFinding issue = new RawFinding(FindingKind.ISSUE, FindingCategory.PERFORMANCE, Severity.LOW, "a.py", 1, 1,
				"Title", longText, longText, longText);

		Long id = findingRepository.saveAndFlush(new Finding(analysis, issue, longText, 1)).getId();
		entityManager.clear();

		assertThat(findingRepository.findById(id).orElseThrow().getExplanation()).hasSize(20_000);
	}

	@Test
	void completedReviewLeadsToItsAnalysisAndTheStateWrittenByTheJob() {
		Analysis analysis = analysisRepository.saveAndFlush(analysis());
		UUID id = reviewRepository.saveAndFlush(new Review(savedUserId(), REPO)).getId();
		// The entity has no way to complete a review yet, so the job's writes are made here.
		jdbcTemplate.update("""
				UPDATE reviews
				SET status = 'COMPLETED', stage = 'SCORING', branch = 'main', commit_sha = ?, analysis_id = ?,
						chunks_done = 2, chunks_total = 2, attempts = 1, cache_hit = true,
						started_at = now(), finished_at = now()
				WHERE id = ?""", SHA, analysis.getId(), id);
		entityManager.clear();

		Review stored = reviewRepository.findById(id).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(ReviewStatus.COMPLETED);
		assertThat(stored.getStage()).isEqualTo(ReviewStage.SCORING);
		assertThat(stored.getBranch()).isEqualTo("main");
		assertThat(stored.getCommitSha()).isEqualTo(SHA);
		assertThat(stored.getChunksDone()).isEqualTo(2);
		assertThat(stored.getChunksTotal()).isEqualTo(2);
		assertThat(stored.getAttempts()).isEqualTo(1);
		assertThat(stored.isCacheHit()).isTrue();
		assertThat(stored.getStartedAt()).isNotNull();
		assertThat(stored.getFinishedAt()).isNotNull();
		assertThat(stored.getAnalysis().getId()).isEqualTo(analysis.getId());
		assertThat(stored.getAnalysis().getOverallScore()).isEqualTo(72);
	}

	@Test
	void failedReviewIsReadWithItsError() {
		UUID id = reviewRepository.saveAndFlush(new Review(savedUserId(), REPO)).getId();
		jdbcTemplate.update("UPDATE reviews SET status = 'FAILED', error_code = ?, error_message = ? WHERE id = ?",
				"REPO_NOT_FOUND", "Repository not found", id);
		entityManager.clear();

		Review stored = reviewRepository.findById(id).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(ReviewStatus.FAILED);
		assertThat(stored.getErrorCode()).isEqualTo("REPO_NOT_FOUND");
		assertThat(stored.getErrorMessage()).isEqualTo("Repository not found");
	}

	@Test
	void findingsOfOneAnalysisAreStoredTogether() {
		Analysis analysis = analysisRepository.saveAndFlush(analysis());
		RawFinding goodPractice = new RawFinding(FindingKind.GOOD_PRACTICE, FindingCategory.SECURITY, null, null,
				null, null, "Title", null, null, null);

		findingRepository.saveAllAndFlush(List.of(new Finding(analysis, goodPractice, null, null),
				new Finding(analysis, goodPractice, null, null)));

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM findings WHERE analysis_id = ?", Integer.class,
				analysis.getId())).isEqualTo(2);
	}

	@Test
	void findingsThatPassValidationSatisfyTheConstraintsOfTheTable() {
		ReviewChunk chunk = new ReviewChunk(List.of(new SourceFile("a.py", "x = 1\n"),
				new SourceFile("b.py", "a\nb\nc\n"), new SourceFile("c.py", "a\r\nb"), new SourceFile("d.py", "y")));
		ValidationResult result = findingValidator.validate(chunk, new FakeReviewProvider().review(chunk));
		Analysis analysis = analysisRepository.saveAndFlush(analysis());

		for (VerifiedFinding verified : result.accepted()) {
			findingRepository.save(
					new Finding(analysis, verified.finding(), verified.codeSnippet(), verified.snippetStartLine()));
		}
		findingRepository.flush();

		assertThat(result.accepted()).hasSize(5);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM findings WHERE analysis_id = ?", Integer.class,
				analysis.getId())).isEqualTo(5);
	}

	private long savedUserId() {
		return userRepository.saveAndFlush(new User("review-" + UUID.randomUUID() + "@example.com", "hash")).getId();
	}

	private static Analysis analysis() {
		return new Analysis(REPO, SHA, "FAKE", "fake-1", 1, 72, 0, 100, 85, 12, 3, 2, 1840, 1);
	}

}
