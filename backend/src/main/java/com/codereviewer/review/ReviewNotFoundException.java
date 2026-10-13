package com.codereviewer.review;

import com.codereviewer.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown for a review that does not exist and for one that belongs to another user. The two
 * are answered alike: a different answer would reveal that the id exists.
 */
public class ReviewNotFoundException extends ApiException {

	public ReviewNotFoundException() {
		super(HttpStatus.NOT_FOUND, "Review not found");
	}

}
