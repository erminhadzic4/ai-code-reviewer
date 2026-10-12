package com.codereviewer.review;

import com.codereviewer.provider.FindingCategory;
import com.codereviewer.provider.FindingKind;
import com.codereviewer.provider.RawFinding;
import com.codereviewer.provider.Severity;
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

/** One finding of an analysis: an issue to fix or a good practice. */
@Entity
@Table(name = "findings")
public class Finding {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "analysis_id", nullable = false, updatable = false)
	private Analysis analysis;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 15)
	private FindingKind kind;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 20)
	private FindingCategory category;

	@Enumerated(EnumType.STRING)
	@Column(updatable = false, length = 10)
	private Severity severity;

	@Column(name = "file_path", updatable = false, length = 1024)
	private String filePath;

	@Column(name = "line_start", updatable = false)
	private Integer lineStart;

	@Column(name = "line_end", updatable = false)
	private Integer lineEnd;

	@Column(nullable = false, updatable = false, length = 200)
	private String title;

	@Column(updatable = false, columnDefinition = "text")
	private String explanation;

	@Column(updatable = false, columnDefinition = "text")
	private String suggestion;

	@Column(name = "suggested_code", updatable = false, columnDefinition = "text")
	private String suggestedCode;

	@Column(name = "code_snippet", updatable = false, columnDefinition = "text")
	private String codeSnippet;

	@Column(name = "snippet_start_line", updatable = false)
	private Integer snippetStartLine;

	protected Finding() {
		// required by JPA
	}

	/**
	 * @param reported the finding as the provider gave it, already validated
	 * @param codeSnippet the lines the finding covers with their context, taken from the file
	 * by the backend; {@code null} for a finding without a location
	 * @param snippetStartLine line number of the first line of {@code codeSnippet}
	 */
	public Finding(Analysis analysis, RawFinding reported, String codeSnippet, Integer snippetStartLine) {
		this.analysis = analysis;
		this.kind = reported.kind();
		this.category = reported.category();
		this.severity = reported.severity();
		this.filePath = reported.filePath();
		this.lineStart = reported.lineStart();
		this.lineEnd = reported.lineEnd();
		this.title = reported.title();
		this.explanation = reported.explanation();
		this.suggestion = reported.suggestion();
		this.suggestedCode = reported.suggestedCode();
		this.codeSnippet = codeSnippet;
		this.snippetStartLine = snippetStartLine;
	}

	public Long getId() {
		return id;
	}

	public Analysis getAnalysis() {
		return analysis;
	}

	public FindingKind getKind() {
		return kind;
	}

	public FindingCategory getCategory() {
		return category;
	}

	public Severity getSeverity() {
		return severity;
	}

	public String getFilePath() {
		return filePath;
	}

	public Integer getLineStart() {
		return lineStart;
	}

	public Integer getLineEnd() {
		return lineEnd;
	}

	public String getTitle() {
		return title;
	}

	public String getExplanation() {
		return explanation;
	}

	public String getSuggestion() {
		return suggestion;
	}

	public String getSuggestedCode() {
		return suggestedCode;
	}

	public String getCodeSnippet() {
		return codeSnippet;
	}

	public Integer getSnippetStartLine() {
		return snippetStartLine;
	}

}
