package com.codereviewer.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RegisterRequestTest {

	private static final String VALID_EMAIL = "ana@example.com";
	private static final String VALID_PASSWORD = "correct-horse";

	private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
	private static final Validator VALIDATOR = FACTORY.getValidator();

	@AfterAll
	static void closeFactory() {
		FACTORY.close();
	}

	@Test
	void validRequestHasNoViolations() {
		assertThat(violations(VALID_EMAIL, VALID_PASSWORD)).isEmpty();
	}

	@Test
	void emailIsTrimmedAndLowerCased() {
		assertThat(new RegisterRequest("  Ana@Example.COM ", VALID_PASSWORD).email()).isEqualTo(VALID_EMAIL);
		assertThat(new LoginRequest("  Ana@Example.COM ", VALID_PASSWORD).email()).isEqualTo(VALID_EMAIL);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   ", "ana", "ana@", "@example.com", "ana@localhost", "ana example@x.com" })
	void invalidEmailIsRejected(String email) {
		assertThat(violations(email, VALID_PASSWORD)).containsExactly("email: must be a valid email address");
	}

	@Test
	void missingFieldsAreRejected() {
		assertThat(violations(null, null)).containsExactly("email: is required", "password: is required");
	}

	@Test
	void emailLongerThan255CharactersIsRejected() {
		// Long domain, short labels: an address that is valid in every respect except its length.
		String label = "a".repeat(62);
		String email = "ana@" + label + "." + label + "." + label + "." + label + "a";

		assertThat(email).hasSize(256);
		assertThat(violations(email, VALID_PASSWORD)).containsExactly("email: must be at most 255 characters");
		assertThat(violations(email.substring(1), VALID_PASSWORD)).isEmpty();
	}

	@Test
	void passwordNeedsAtLeast8Characters() {
		assertThat(violations(VALID_EMAIL, "1234567")).containsExactly("password: must be at least 8 characters");
		assertThat(violations(VALID_EMAIL, "12345678")).isEmpty();
	}

	@Test
	void passwordMayHaveAtMost72Bytes() {
		assertThat(violations(VALID_EMAIL, "a".repeat(72))).isEmpty();
		assertThat(violations(VALID_EMAIL, "a".repeat(73))).containsExactly("password: must be at most 72 bytes");
	}

	@Test
	void passwordLimitCountsBytesNotCharacters() {
		// "š" takes two bytes in UTF-8: 36 of them fill the limit, 37 exceed it with only 37 characters.
		assertThat(violations(VALID_EMAIL, "š".repeat(36))).isEmpty();
		assertThat(violations(VALID_EMAIL, "š".repeat(37))).containsExactly("password: must be at most 72 bytes");
	}

	@Test
	void loginOnlyRequiresBothFields() {
		assertThat(VALIDATOR.validate(new LoginRequest(VALID_EMAIL, "short"))).isEmpty();
		assertThat(VALIDATOR.validate(new LoginRequest(" ", ""))).hasSize(2);
		assertThat(VALIDATOR.validate(new LoginRequest(null, null))).hasSize(2);
	}

	private static List<String> violations(String email, String password) {
		return VALIDATOR.validate(new RegisterRequest(email, password))
				.stream()
				.map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
				.sorted()
				.toList();
	}

}
