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

	@Column(name = "error_message", length = 500)
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
