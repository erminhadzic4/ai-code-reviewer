package com.codereviewer.review.job;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.codereviewer.github.SourceFile;
import com.codereviewer.provider.CodeReviewProvider;
import com.codereviewer.provider.FindingValidator;
import com.codereviewer.provider.NumberedSource;
import com.codereviewer.provider.ProviderErrorCode;
import com.codereviewer.provider.ProviderException;
import com.codereviewer.provider.ReviewChunk;
import com.codereviewer.provider.ValidationResult;
import com.codereviewer.provider.VerifiedFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sends the chunks of a repository to the provider, one after another, and collects the
 * findings that pass validation.
 *
 * <p>Two kinds of failure are handled here, because a failed job starts again from the first
 * chunk and would spend the quota of every chunk already reviewed:
 * <ul>
 * <li>A failure that passes by itself (a rate limit, a server error, an answer that is not
 * the agreed JSON): the same chunk is sent again after a pause, at most
 * {@value #MAX_SENDS} times.
 * <li>A failure caused by the content (the answer was cut off, or the provider refused the
 * content): sending the same chunk again gives the same result, so a chunk with several
 * files is sent once more as two halves. Files that still cannot be reviewed are counted as
 * failed and the analysis goes on without them.
 * </ul>
 * Everything else ends the analysis with the provider's exception, and the job decides.
 */
@Component
public class ChunkAnalyzer {

	/** How often one chunk is sent before the failure is handed to the job. */
	static final int MAX_SENDS = 3;

	/** Pause before the second and the third sending, when the provider gave no time. */
	static final List<Duration> RETRY_DELAYS = List.of(Duration.ofSeconds(5), Duration.ofSeconds(20));

	/** A longer wait is the job's business: it can postpone the review instead of sleeping. */
	static final Duration MAX_WAIT = Duration.ofSeconds(60);

	private static final Logger log = LoggerFactory.getLogger(ChunkAnalyzer.class);

	private final CodeReviewProvider provider;
	private final FindingValidator validator;
	private final RequestPacer pacer;
	private final Clock clock;
	private final Sleeper sleeper;

	public ChunkAnalyzer(CodeReviewProvider provider, FindingValidator validator, RequestPacer pacer, Clock clock,
			Sleeper sleeper) {
		this.provider = provider;
		this.validator = validator;
		this.pacer = pacer;
		this.clock = clock;
		this.sleeper = sleeper;
	}

	/**
	 * @param progress told after every chunk how many are done and how many there are; the
	 * total grows by one when a chunk is split
	 * @throws ProviderException if a chunk fails for a reason other than its content and
	 * sending it again did not help, or if no chunk at all could be reviewed
	 * @throws InterruptedException if the thread is interrupted while waiting
	 */
	public ChunkAnalysis analyze(List<ReviewChunk> chunks, Progress progress) throws InterruptedException {
		Tally tally = new Tally();
		int total = chunks.size();
		int done = 0;
		for (ReviewChunk chunk : chunks) {
			ProviderException refused = review(chunk, tally);
			if (refused != null && chunk.files().size() > 1) {
				// Two halves in place of the chunk: one more piece of work than before.
				total++;
				for (ReviewChunk half : halves(chunk)) {
					ProviderException refusedAgain = review(half, tally);
					if (refusedAgain != null) {
						tally.failed(half, refusedAgain);
					}
					progress.report(++done, total);
				}
				continue;
			}
			if (refused != null) {
				tally.failed(chunk, refused);
			}
			progress.report(++done, total);
		}
		if (tally.chunksReviewed == 0 && tally.lastFailure != null) {
			throw tally.lastFailure;
		}
		return tally.result();
	}

	/**
	 * Sends one chunk until it is reviewed. Returns {@code null} when it was, and the
	 * provider's exception when the content itself cannot be reviewed.
	 */
	private ProviderException review(ReviewChunk chunk, Tally tally) throws InterruptedException {
		int cost = cost(chunk);
		for (int send = 1;; send++) {
			pacer.await(cost);
			try {
				tally.reviewed(chunk, validator.validate(chunk, provider.review(chunk)));
				return null;
			}
			catch (ProviderException ex) {
				if (dependsOnTheContent(ex)) {
					return ex;
				}
				if (!ex.isRetryable() || send == MAX_SENDS) {
					throw ex;
				}
				Duration wait = ex.getRetryAt()
						.map(retryAt -> Duration.between(clock.instant(), retryAt))
						.orElse(RETRY_DELAYS.get(send - 1));
				if (wait.compareTo(MAX_WAIT) > 0) {
					throw ex;
				}
				log.info("Provider failed with {}; sending the chunk again in {} s (sending {} of {})", ex.getCode(),
						Math.max(wait.toSeconds(), 0), send + 1, MAX_SENDS);
				sleeper.sleep(wait);
			}
		}
	}

	private static boolean dependsOnTheContent(ProviderException ex) {
		return ex.getCode() == ProviderErrorCode.PROVIDER_OUTPUT_TRUNCATED
				|| ex.getCode() == ProviderErrorCode.PROVIDER_BLOCKED;
	}

	// By number of files, in their order. Halves are not split again: a chunk costs at most
	// two more requests.
	private static List<ReviewChunk> halves(ReviewChunk chunk) {
		List<SourceFile> files = chunk.files();
		int middle = (files.size() + 1) / 2;
		return List.of(new ReviewChunk(files.subList(0, middle)), new ReviewChunk(files.subList(middle, files.size())));
	}

	/** The same measure the chunks were filled by and the provider's limit is stated in. */
	private static int cost(ReviewChunk chunk) {
		return Math.toIntExact(chunk.files().stream().mapToLong(NumberedSource::length).sum());
	}

	/** Receives the progress of an analysis. */
	@FunctionalInterface
	public interface Progress {

		void report(int chunksDone, int chunksTotal);

	}

	/** Adds up the chunks as they finish. */
	private static final class Tally {

		private final List<VerifiedFinding> findings = new ArrayList<>();
		private int findingsDiscarded;
		private int filesAnalyzed;
		private int filesFailed;
		private int linesAnalyzed;
		private int chunksReviewed;
		private ProviderException lastFailure;

		void reviewed(ReviewChunk chunk, ValidationResult result) {
			findings.addAll(result.accepted());
			findingsDiscarded += result.discarded();
			filesAnalyzed += chunk.files().size();
			linesAnalyzed += chunk.files().stream().mapToInt(file -> file.lines().size()).sum();
			chunksReviewed++;
		}

		void failed(ReviewChunk chunk, ProviderException cause) {
			filesFailed += chunk.files().size();
			lastFailure = cause;
			log.warn("Provider could not review {} file(s) ({}): {}", chunk.files().size(), cause.getCode(),
					chunk.files().stream().map(SourceFile::path).toList());
		}

		ChunkAnalysis result() {
			return new ChunkAnalysis(findings, findingsDiscarded, filesAnalyzed, filesFailed, linesAnalyzed);
		}

	}

}
