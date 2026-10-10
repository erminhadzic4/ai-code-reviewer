package com.codereviewer.auth.dto;

import java.util.Locale;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
		@NotNull(message = "is required")
		@Size(max = 255, message = "must be at most 255 characters")
		// The default check accepts "a@b"; a real address has a dot in its domain.
		@Email(regexp = ".+@.+\\..+", message = "must be a valid email address")
		String email,

		@NotNull(message = "is required")
		@Size(min = 8, message = "must be at least 8 characters")
		@MaxBytes(value = 72, message = "must be at most 72 bytes")
		String password) {

	public RegisterRequest {
		email = normalizeEmail(email);
	}

	/** Emails are compared and stored trimmed and in lower case. */
	static String normalizeEmail(String email) {
		return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
	}

}
