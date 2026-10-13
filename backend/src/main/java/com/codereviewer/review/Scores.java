package com.codereviewer.review;

/**
 * The scores of an analysis, each between 0 and 100.
 *
 * @param overall weighted average of the three category scores
 */
public record Scores(int overall, int security, int maintainability, int performance) {
}
