package com.codereviewer.common;

import org.springframework.http.HttpStatus;

/**
 * An error the client is told about: it carries the HTTP status and a message that is
 * safe to show. Features define their own subclasses.
 */
public abstract class ApiException extends RuntimeException {

	private final HttpStatus status;

	protected ApiException(HttpStatus status, String detail) {
		super(detail);
		this.status = status;
	}

	public HttpStatus getStatus() {
		return status;
	}

}
