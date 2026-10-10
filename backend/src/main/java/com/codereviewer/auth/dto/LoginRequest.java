package com.codereviewer.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Only checks that both fields are present. The registration rules are not repeated here:
 * a password that breaks them is simply a wrong password.
 */
public record LoginRequest(
		@NotBlank(message = "is required") String email,
		@NotBlank(message = "is required") String password) {

	public LoginRequest {
		email = RegisterRequest.normalizeEmail(email);
	}

}
