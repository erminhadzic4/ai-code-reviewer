package com.codereviewer.auth;

import com.codereviewer.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a token is valid but its user no longer exists. The response matches the
 * one given for a missing or invalid token.
 */
public class AuthenticationRequiredException extends ApiException {

	public AuthenticationRequiredException() {
		super(HttpStatus.UNAUTHORIZED, "Authentication required");
	}

}
