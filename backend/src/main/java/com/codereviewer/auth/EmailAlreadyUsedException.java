package com.codereviewer.auth;

import com.codereviewer.common.ApiException;
import org.springframework.http.HttpStatus;

public class EmailAlreadyUsedException extends ApiException {

	public EmailAlreadyUsedException() {
		super(HttpStatus.CONFLICT, "Email is already registered");
	}

}
