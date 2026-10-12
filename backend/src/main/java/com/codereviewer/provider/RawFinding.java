package com.codereviewer.provider;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One finding as a provider reported it. Nothing here is checked yet: any field may be
 * missing and the lines may not exist in the file. The constraints only state the rules for
 * a single field; {@link FindingValidator} applies them together with the rules that span
 * several fields.
 *
 * @param severity set for an issue, {@code null} for a good practice
 * @param filePath path as given in the chunk; optional for a good practice
 * @param lineStart first line the finding covers, counted from 1
 * @param lineEnd last line the finding covers, inclusive
 * @param explanation why it matters
 * @param suggestion the suggested fix, in prose
 * @param suggestedCode replacement for the lines {@code lineStart..lineEnd}
 */
public record RawFinding(
		@NotNull FindingKind kind,
		@NotNull FindingCategory category,
		Severity severity,
		@Size(max = MAX_PATH_LENGTH) String filePath,
		Integer lineStart,
		Integer lineEnd,
		@NotBlank @Size(max = MAX_TITLE_LENGTH) String title,
		@Size(max = MAX_TEXT_LENGTH) String explanation,
		@Size(max = MAX_TEXT_LENGTH) String suggestion,
		@Size(max = MAX_CODE_LENGTH) String suggestedCode) {

	/** The width of the {@code title} column. */
	public static final int MAX_TITLE_LENGTH = 200;

	/** The width of the {@code file_path} column. */
	public static final int MAX_PATH_LENGTH = 1024;

	/** Explanations and suggestions are a few sentences; anything longer is not one. */
	public static final int MAX_TEXT_LENGTH = 800;

	/** Suggested code replaces a few lines, not a file. */
	public static final int MAX_CODE_LENGTH = 2400;

}
