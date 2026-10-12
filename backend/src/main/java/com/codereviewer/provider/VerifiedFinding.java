package com.codereviewer.provider;

/**
 * A finding that passed every check, together with the code it is about.
 *
 * @param finding the finding as reported; its file and lines exist in the chunk
 * @param codeSnippet the lines the finding covers with one line of context on each side,
 * taken from the file and never from the provider; {@code null} for a finding without lines
 * @param snippetStartLine line number of the first line of {@code codeSnippet}
 */
public record VerifiedFinding(RawFinding finding, String codeSnippet, Integer snippetStartLine) {
}
