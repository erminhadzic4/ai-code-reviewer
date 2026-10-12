package com.codereviewer.review;

import java.time.Instant;

import com.codereviewer.github.RepoRef;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The result of analysing one repository at one commit. It is shared by every review of that
 * commit and never changes once written, which is what makes it usable as a cache.
 */
@Entity
@Table(name = "analyses")
public class Analysis {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "repo_owner", nullable = false, updatable = false, length = 39)
	private String repoOwner;

	@Column(name = "repo_name", nullable = false, updatable = false, length = 100)
	private String repoName;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "commit_sha", nullable = false, updatable = false, length = 40)
	private String commitSha;

	@Column(nullable = false, updatable = false, length = 20)
	private String provider;

	@Column(nullable = false, updatable = false, length = 100)
	private String model;

	@Column(name = "prompt_version", nullable = false, updatable = false)
	private int promptVersion;

	@JdbcTypeCode(SqlTypes.SMALLINT)
	@Column(name = "overall_score", nullable = false, updatable = false)
	private int overallScore;

	@JdbcTypeCode(SqlTypes.SMALLINT)
	@Column(name = "security_score", nullable = false, updatable = false)
	private int securityScore;

	@JdbcTypeCode(SqlTypes.SMALLINT)
	@Column(name = "maintainability_score", nullable = false, updatable = false)
	private int maintainabilityScore;

	@JdbcTypeCode(SqlTypes.SMALLINT)
	@Column(name = "performance_score", nullable = false, updatable = false)
	private int performanceScore;

	@Column(name = "files_analyzed", nullable = false, updatable = false)
	private int filesAnalyzed;

	@Column(name = "files_skipped", nullable = false, updatable = false)
	private int filesSkipped;

	@Column(name = "findings_discarded", nullable = false, updatable = false)
	private int findingsDiscarded;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected Analysis() {
		// required by JPA
	}

	/**
	 * @param commitSha full 40-character SHA in lower case
	 * @param provider name of the provider that produced the findings
	 * @param overallScore each score is between 0 and 100, computed by the backend
	 * @param findingsDiscarded findings dropped because they failed validation
	 */
	public Analysis(RepoRef repo, String commitSha, String provider, String model, int promptVersion,
			int overallScore, int securityScore, int maintainabilityScore, int performanceScore, int filesAnalyzed,
			int filesSkipped, int findingsDiscarded) {
		this.repoOwner = repo.owner();
		this.repoName = repo.name();
		this.commitSha = commitSha;
		this.provider = provider;
		this.model = model;
		this.promptVersion = promptVersion;
		this.overallScore = overallScore;
		this.securityScore = securityScore;
		this.maintainabilityScore = maintainabilityScore;
		this.performanceScore = performanceScore;
		this.filesAnalyzed = filesAnalyzed;
		this.filesSkipped = filesSkipped;
		this.findingsDiscarded = findingsDiscarded;
		this.createdAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public String getRepoOwner() {
		return repoOwner;
	}

	public String getRepoName() {
		return repoName;
	}

	public String getCommitSha() {
		return commitSha;
	}

	public String getProvider() {
		return provider;
	}

	public String getModel() {
		return model;
	}

	public int getPromptVersion() {
		return promptVersion;
	}

	public int getOverallScore() {
		return overallScore;
	}

	public int getSecurityScore() {
		return securityScore;
	}

	public int getMaintainabilityScore() {
		return maintainabilityScore;
	}

	public int getPerformanceScore() {
		return performanceScore;
	}

	public int getFilesAnalyzed() {
		return filesAnalyzed;
	}

	public int getFilesSkipped() {
		return filesSkipped;
	}

	public int getFindingsDiscarded() {
		return findingsDiscarded;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
