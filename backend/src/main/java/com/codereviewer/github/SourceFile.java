package com.codereviewer.github;

/**
 * One file kept for review.
 *
 * @param path path relative to the repository root, with {@code /} between segments
 * @param content the file as text
 */
public record SourceFile(String path, String content) {
}
