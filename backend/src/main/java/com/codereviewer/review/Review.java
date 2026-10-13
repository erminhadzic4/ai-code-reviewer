package com.codereviewer.review;

import java.time.Instant;
import java.util.UUID;

import com.codereviewer.github.RepoRef;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One user's request to review a repository. It carries the state of the job; the result
 * lives in the {@link Analysis} it points to once it is completed, which other reviews of
 * the same commit share.
 */
@Entity
@Table(name = "reviews")
public class Review {

	static final int MAX_ERROR_MESSAGE_LENGTH = 500;

	// A UUID because the id appears in URLs, where a sequential number could be guessed.
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	// The id only: a request knows its user by id, and a review never needs the user's data.
	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(name = "repo_owner", nullable = false, updatable = false, length = 39)
	private String repoOwner;

	@Column(name = "repo_name", nullable = false, updatable = false, length = 100)
	private String repoName;

	private String branch;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "commit_sha", length = 40)
	private String commitSha;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ReviewStatus status;

	@Enumerated(EnumType.STRING)
	@Column(length = 20)
	private ReviewStage stage;

	@Column(name = "chunks_done", nullable = false)
	private int chunksDone;

	@Column(name = "chunks_total", nullable = false)
	private int chunksTotal;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "run_after", nullable = false)
	private Instant runAfter;

	@Column(name = "error_code", length = 40)
	private String errorCode;

	@Column(name = "error_message", length = MAX_ERROR_MESSAGE_LENGTH)
	private String errorMessage;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "analysis_id")
	private Analysis analysis;

	@Column(name = "cache_hit", nullable = false)
	private boolean cacheHit;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "finished_at")
	private Instant finishedAt;

	protected Review() {
		// required by JPA
	}

	/** A new review, queued and due at once. */
	public Review(long userId, RepoRef repo) {
		this.userId = userId;
		this.repoOwner = repo.owner();
		this.repoName = repo.name();
		this.status = ReviewStatus.QUEUED;
		this.createdAt = Instant.now();
		this.runAfter = this.createdAt;
	}

	// State transitions. Each one checks the state it starts from, so a job cannot skip a
	// step or finish a review twice; the caller supplies the time.

	/** A worker takes the review: the next attempt begins, with no progress yet. */
	public void start(Instant now) {
		require(status == ReviewStatus.QUEUED, "start");
		status = ReviewStatus.RUNNING;
		stage = ReviewStage.FETCHING;
		attempts++;
		chunksDone = 0;
		chunksTotal = 0;
		startedAt = now;
	}

	/** The commit that this attempt reviews is known. */
	public void fetched(String branch, String commitSha) {
		require(status == ReviewStatus.RUNNING, "record the commit of");
		this.branch = branch;
		this.commitSha = commitSha;
	}

	/** The files are split into chunks and the first one is about to be sent. */
	public void analyzing(int chunksTotal) {
		require(status == ReviewStatus.RUNNING, "analyse");
		setProgress(0, chunksTotal);
		stage = ReviewStage.ANALYZING;
	}

	/** The total can grow while the analysis runs, when a chunk has to be split. */
	public void progress(int chunksDone, int chunksTotal) {
		require(status == ReviewStatus.RUNNING && stage == ReviewStage.ANALYZING, "report progress of");
		setProgress(chunksDone, chunksTotal);
	}

	public void scoring() {
		require(status == ReviewStatus.RUNNING, "score");
		stage = ReviewStage.SCORING;
	}

	/**
	 * @param analysis the result, stored already
	 * @param cacheHit whether the analysis existed before this review ran
	 */
	public void complete(Analysis analysis, boolean cacheHit, Instant now) {
		require(status == ReviewStatus.RUNNING, "complete");
		if (analysis == null) {
			throw new IllegalArgumentException("A completed review needs its analysis");
		}
		status = ReviewStatus.COMPLETED;
		stage = null;
		this.analysis = analysis;
		this.cacheHit = cacheHit;
		finishedAt = now;
	}

	/** Back into the queue, for another attempt no earlier than {@code runAfter}. */
	public void requeue(Instant runAfter) {
		require(status == ReviewStatus.RUNNING, "requeue");
		status = ReviewStatus.QUEUED;
		stage = null;
		chunksDone = 0;
		chunksTotal = 0;
		this.runAfter = runAfter;
	}

	/**
	 * @param errorCode name of the error code, stored as it is
	 * @param errorMessage a message that is safe to show; cut to what the column holds
	 */
	public void fail(String errorCode, String errorMessage, Instant now) {
		require(status == ReviewStatus.RUNNING, "fail");
		if (errorCode == null || errorCode.isBlank()) {
			throw new IllegalArgumentException("A failed review needs an error code");
		}
		status = ReviewStatus.FAILED;
		stage = null;
		this.errorCode = errorCode;
		this.errorMessage = errorMessage == null || errorMessage.length() <= MAX_ERROR_MESSAGE_LENGTH ? errorMessage
				: errorMessage.substring(0, MAX_ERROR_MESSAGE_LENGTH);
		finishedAt = now;
	}

	private void setProgress(int chunksDone, int chunksTotal) {
		if (chunksDone < 0 || chunksDone > chunksTotal) {
			throw new IllegalArgumentException("Progress must be between 0 and the total");
		}
		this.chunksDone = chunksDone;
		this.chunksTotal = chunksTotal;
	}

	private void require(boolean allowed, String action) {
		if (!allowed) {
			String state = stage == null ? status.name() : status + " (" + stage + ")";
			throw new IllegalStateException("Cannot " + action + " a review that is " + state);
		}
	}

	public UUID getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public String getRepoOwner() {
		return repoOwner;
	}

	public String getRepoName() {
		return repoName;
	}

	public String getBranch() {
		return branch;
	}

	public String getCommitSha() {
		return commitSha;
	}

	public ReviewStatus getStatus() {
		return status;
	}

	public ReviewStage getStage() {
		return stage;
	}

	public int getChunksDone() {
		return chunksDone;
	}

	public int getChunksTotal() {
		return chunksTotal;
	}

	public int getAttempts() {
		return attempts;
	}

	public Instant getRunAfter() {
		return runAfter;
	}

	public String getErrorCode() {
		return errorCode;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public Analysis getAnalysis() {
		return analysis;
	}

	public boolean isCacheHit() {
		return cacheHit;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getFinishedAt() {
		return finishedAt;
	}

}
