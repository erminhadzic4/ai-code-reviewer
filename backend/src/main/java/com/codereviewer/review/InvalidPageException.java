package com.codereviewer.review;

import com.codereviewer.common.ApiException;
import org.springframework.http.HttpStatus;

/** Thrown for a page number below 0 or a page size below 1. */
public class InvalidPageException extends ApiException {

	public InvalidPageException() {
		super(HttpStatus.BAD_REQUEST, "Page must be 0 or more and size 1 or more");
	}

}
