package com.codereviewer.review;

import com.codereviewer.common.ApiException;
import org.springframework.http.HttpStatus;

/** Thrown when the report of a review is asked for and the review has none. */
public class ReviewNotCompletedException extends ApiException {

	public ReviewNotCompletedException(ReviewStatus status) {
		super(HttpStatus.CONFLICT, status == ReviewStatus.FAILED ? "The review failed and has no report"
				: "The review is not completed yet");
	}

}
