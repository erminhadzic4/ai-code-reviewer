package com.codereviewer.review.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Only checks that a URL was sent. Whether it names a GitHub repository is decided by the
 * parser, which gives the more useful message.
 */
public record SubmitReviewRequest(@NotBlank(message = "is required") String repoUrl) {
}
