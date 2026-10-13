package com.codereviewer.review.job;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.codereviewer.github.GitHubClient;
import com.codereviewer.github.GitHubException;
import com.codereviewer.github.RepoHead;
import com.codereviewer.github.RepoRef;
import com.codereviewer.github.RepoSnapshot;
import com.codereviewer.provider.CodeReviewProvider;
import com.codereviewer.provider.FindingKind;
import com.codereviewer.provider.ProviderException;
import com.codereviewer.provider.ReviewChunk;
import com.codereviewer.provider.VerifiedFinding;
import com.codereviewer.review.Analysis;
import com.codereviewer.review.Review;
import com.codereviewer.review.ScoreCalculator;
import com.codereviewer.review.Scores;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The steps of one review, in order: find the commit, look for an analysis of it, download
 * the files, have them reviewed, score the findings and store the result.
 *
 * <p>Nothing is retried and no failure is handled here. A step that fails throws, and the
 * caller decides whether the review is tried again. Every write goes through
 * {@link ReviewJobStore} in a short transaction of its own, so none is open during a request
 * to GitHub or to the provider.
 */
@Component
public class ReviewPipeline {

	private static final Logger log = LoggerFactory.getLogger(ReviewPipeline.class);

	private final GitHubClient github;
	private final Chunker chunker;
	private final ChunkAnalyzer analyzer;
	private final CodeReviewProvider provider;
	private final ReviewJobStore store;
	private final Clock clock;

	public ReviewPipeline(GitHubClient github, Chunker chunker, ChunkAnalyzer analyzer, CodeReviewProvider provider,
			ReviewJobStore store, Clock clock) {
		this.github = github;
		this.chunker = chunker;
		this.analyzer = analyzer;
		this.provider = provider;
		this.store = store;
		this.clock = clock;
	}

	/**
	 * Runs a review that has been claimed until it is completed.
	 *
	 * @throws GitHubException if the repository cannot be read
	 * @throws ProviderException if the provider cannot review it
	 * @throws ReviewFailedException if there is nothing in it to review
	 * @throws InterruptedException if the thread is interrupted while waiting
	 */
	public void run(Review review) throws InterruptedException {
		UUID id = review.getId();
		RepoHead head = github.resolveHead(new RepoRef(review.getRepoOwner(), review.getRepoName()));
		store.markFetched(id, head.defaultBranch(), head.commitSha());

		// Under the name GitHub uses now: after a rename both names lead to the same analysis.
		Optional<Analysis> cached = store.findCached(head.repo(), head.commitSha(), provider);
		if (cached.isPresent()) {
			store.completeFromCache(id, cached.get().getId(), clock.instant());
			log.info("Review {} completed from the analysis of {} that already exists", id, head.commitSha());
			return;
		}

		RepoSnapshot snapshot = github.fetchSnapshot(head);
		int filesSkipped = snapshot.skipped().values().stream().mapToInt(Integer::intValue).sum();
		if (snapshot.files().isEmpty()) {
			// Not an empty analysis: it would give a repository of documents a score of 100.
			throw new ReviewFailedException(ReviewErrorCode.NO_REVIEWABLE_FILES, nothingToReview(filesSkipped));
		}

		List<ReviewChunk> chunks = chunker.chunk(snapshot.files());
		store.markAnalyzing(id, chunks.size());
		ChunkAnalysis analysis = analyzer.analyze(chunks, (done, total) -> store.progress(id, done, total));

		store.markScoring(id);
		List<VerifiedFinding> findings = oneGoodPracticePerTitle(analysis.findings());
		Scores scores = ScoreCalculator.calculate(findings, analysis.linesAnalyzed());
		Analysis result = new Analysis(head.repo(), head.commitSha(), provider.name(), provider.model(),
				provider.promptVersion(), scores.overall(), scores.security(), scores.maintainability(),
				scores.performance(), analysis.filesAnalyzed(), filesSkipped, analysis.filesFailed(),
				analysis.linesAnalyzed(), analysis.findingsDiscarded());
		store.complete(id, result, findings, clock.instant());
		log.info("Review {} completed: {} files, {} findings, overall score {}", id, analysis.filesAnalyzed(),
				findings.size(), scores.overall());
	}

	/**
	 * Keeps the first good practice of every title and every issue. A provider sees one chunk
	 * at a time and praises each of them for the same thing; over a repository that is one
	 * good practice, not one per chunk.
	 */
	static List<VerifiedFinding> oneGoodPracticePerTitle(List<VerifiedFinding> findings) {
		Set<String> titles = new HashSet<>();
		List<VerifiedFinding> kept = new ArrayList<>(findings.size());
		for (VerifiedFinding finding : findings) {
			boolean goodPractice = finding.finding().kind() == FindingKind.GOOD_PRACTICE;
			if (!goodPractice || titles.add(finding.finding().title().strip().toLowerCase(Locale.ROOT))) {
				kept.add(finding);
			}
		}
		return List.copyOf(kept);
	}

	private static String nothingToReview(int filesSkipped) {
		return switch (filesSkipped) {
			case 0 -> "Nothing to review: this repository has no files";
			case 1 -> "Nothing to review: the only file in this repository was filtered out";
			default -> "Nothing to review: all " + filesSkipped + " files in this repository were filtered out";
		};
	}

}
