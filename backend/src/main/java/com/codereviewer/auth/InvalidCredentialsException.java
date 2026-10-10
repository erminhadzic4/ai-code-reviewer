package com.codereviewer.auth;

import com.codereviewer.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown for an unknown email and for a wrong password alike, so that the response does
 * not reveal which of the two it was.
 */
public class InvalidCredentialsException extends ApiException {

	public InvalidCredentialsException() {
		super(HttpStatus.UNAUTHORIZED, "Invalid email or password");
	}

}
