package com.codereviewer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import com.codereviewer.auth.dto.AuthResponse;
import com.codereviewer.auth.dto.LoginRequest;
import com.codereviewer.auth.dto.RegisterRequest;
import com.codereviewer.auth.dto.UserResponse;
import com.codereviewer.user.User;
import com.codereviewer.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class AuthServiceTest {

	private static final String EMAIL = "ana@example.com";
	private static final String PASSWORD = "correct-horse";

	private final UserRepository userRepository = mock(UserRepository.class);
	// Lowest BCrypt cost, to keep the tests fast.
	private final PasswordEncoder passwordEncoder = spy(new BCryptPasswordEncoder(4));
	private final JwtService jwtService = new JwtService(
			new JwtProperties("unit-test-secret-0123456789-abcdefghij", Duration.ofHours(24)));

	private final AuthService authService = new AuthService(userRepository, passwordEncoder, jwtService);

	@Test
	void registerStoresANormalisedEmailAndAHashInsteadOfThePassword() {
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> withId(invocation.getArgument(0), 7L));

		authService.register(new RegisterRequest("  Ana@Example.COM ", PASSWORD));

		ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
		verify(userRepository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getEmail()).isEqualTo(EMAIL);
		assertThat(saved.getValue().getPasswordHash()).startsWith("$2").doesNotContain(PASSWORD);
		assertThat(passwordEncoder.matches(PASSWORD, saved.getValue().getPasswordHash())).isTrue();
	}

	@Test
	void registerReturnsATokenForTheNewUser() {
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> withId(invocation.getArgument(0), 7L));

		AuthResponse response = authService.register(new RegisterRequest(EMAIL, PASSWORD));

		assertThat(jwtService.parse(response.accessToken())).contains(7L);
		assertThat(response.expiresIn()).isEqualTo(86_400);
		assertThat(response.user()).isEqualTo(new UserResponse(7L, EMAIL));
	}

	@Test
	void registerRejectsAnEmailThatIsAlreadyRegistered() {
		when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

		assertThatThrownBy(() -> authService.register(new RegisterRequest("ANA@example.com", PASSWORD)))
				.isInstanceOf(EmailAlreadyUsedException.class);
		verify(userRepository, never()).saveAndFlush(any(User.class));
	}

	@Test
	void registerReportsAConcurrentDuplicateAsAlreadyRegistered() {
		when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("uq_users_email"));

		assertThatThrownBy(() -> authService.register(new RegisterRequest(EMAIL, PASSWORD)))
				.isInstanceOf(EmailAlreadyUsedException.class);
	}

	@Test
	void loginWithTheRightPasswordReturnsAToken() {
		givenRegisteredUser();

		AuthResponse response = authService.login(new LoginRequest(" ANA@example.com", PASSWORD));

		assertThat(jwtService.parse(response.accessToken())).contains(7L);
		assertThat(response.user()).isEqualTo(new UserResponse(7L, EMAIL));
	}

	@Test
	void loginFailsTheSameWayForAWrongPasswordAndAnUnknownEmail() {
		givenRegisteredUser();

		assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "wrong-password")))
				.isExactlyInstanceOf(InvalidCredentialsException.class)
				.hasMessage("Invalid email or password");
		assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", PASSWORD)))
				.isExactlyInstanceOf(InvalidCredentialsException.class)
				.hasMessage("Invalid email or password");
	}

	@Test
	void loginForAnUnknownEmailStillChecksAPasswordHash() {
		clearInvocations(passwordEncoder);

		assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", PASSWORD)))
				.isInstanceOf(InvalidCredentialsException.class);

		// Without this the response for an unknown email would come back measurably faster.
		verify(passwordEncoder).matches(eq(PASSWORD), anyString());
	}

	@Test
	void loginWithAPasswordTooLongForBcryptIsJustAWrongPassword() {
		givenRegisteredUser();

		assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "š".repeat(37))))
				.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void currentUserIsLoadedById() {
		when(userRepository.findById(7L)).thenReturn(Optional.of(withId(new User(EMAIL, "hash"), 7L)));

		assertThat(authService.currentUser(new AuthenticatedUser(7L))).isEqualTo(new UserResponse(7L, EMAIL));
	}

	@Test
	void currentUserThatNoLongerExistsIsNotAuthenticated() {
		when(userRepository.findById(7L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> authService.currentUser(new AuthenticatedUser(7L)))
				.isInstanceOf(AuthenticationRequiredException.class);
	}

	private void givenRegisteredUser() {
		User user = withId(new User(EMAIL, passwordEncoder.encode(PASSWORD)), 7L);
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
	}

	// The id is assigned by the database, so the entity has no setter for it.
	private static User withId(User user, long id) {
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}

}
