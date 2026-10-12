package com.codereviewer.provider;

/**
 * One finding as a provider reported it. Nothing here is checked yet: any field may be
 * missing and the lines may not exist in the file.
 *
 * @param severity set for an issue, {@code null} for a good practice
 * @param filePath path as given in the chunk; optional for a good practice
 * @param lineStart first line the finding covers, counted from 1
 * @param lineEnd last line the finding covers, inclusive
 * @param explanation why it matters
 * @param suggestion the suggested fix, in prose
 * @param suggestedCode replacement for the lines {@code lineStart..lineEnd}
 */
public record RawFinding(FindingKind kind, FindingCategory category, Severity severity, String filePath,
		Integer lineStart, Integer lineEnd, String title, String explanation, String suggestion,
		String suggestedCode) {
}
