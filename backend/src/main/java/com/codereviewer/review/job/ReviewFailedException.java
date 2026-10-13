package com.codereviewer.review.job;

/**
 * A review cannot be finished for a reason that lies in the review itself, not in GitHub or
 * in the provider. Trying again does not help.
 */
public class ReviewFailedException extends RuntimeException {

	private final ReviewErrorCode code;

	/**
	 * @param detail a message that is safe to show to the user
	 */
	public ReviewFailedException(ReviewErrorCode code, String detail) {
		super(detail);
		this.code = code;
	}

	public ReviewErrorCode getCode() {
		return code;
	}

}
