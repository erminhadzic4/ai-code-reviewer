package com.codereviewer.auth;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import com.codereviewer.auth.dto.AuthResponse;
import com.codereviewer.auth.dto.LoginRequest;
import com.codereviewer.auth.dto.RegisterRequest;
import com.codereviewer.auth.dto.UserResponse;
import com.codereviewer.user.User;
import com.codereviewer.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

	/** BCrypt reads at most 72 bytes of a password. */
	private static final int MAX_PASSWORD_BYTES = 72;

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;

	/**
	 * Hash of a random value nobody knows. A login for an unknown email is checked against
	 * it, so it takes as long as a login with a wrong password and the response time does
	 * not reveal whether the email is registered.
	 */
	private final String dummyHash;

	public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	public AuthResponse register(RegisterRequest request) {
		if (userRepository.existsByEmail(request.email())) {
			throw new EmailAlreadyUsedException();
		}
		User user = new User(request.email(), passwordEncoder.encode(request.password()));
		try {
			user = userRepository.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException ex) {
			// Two registrations for the same email at the same moment both pass the check
			// above; the unique constraint stops the second one.
			throw new EmailAlreadyUsedException();
		}
		return tokenFor(user);
	}

	public AuthResponse login(LoginRequest request) {
		Optional<User> user = userRepository.findByEmail(request.email());
		String hash = user.map(User::getPasswordHash).orElse(dummyHash);
		boolean passwordMatches = fitsBcrypt(request.password())
				&& passwordEncoder.matches(request.password(), hash);
		if (user.isEmpty() || !passwordMatches) {
			throw new InvalidCredentialsException();
		}
		return tokenFor(user.get());
	}

	public UserResponse currentUser(AuthenticatedUser principal) {
		return userRepository.findById(principal.id())
				.map(AuthService::toResponse)
				.orElseThrow(AuthenticationRequiredException::new);
	}

	private AuthResponse tokenFor(User user) {
		return new AuthResponse(jwtService.issue(user.getId()), jwtService.expiration().toSeconds(),
				toResponse(user));
	}

	private static UserResponse toResponse(User user) {
		return new UserResponse(user.getId(), user.getEmail());
	}

	private static boolean fitsBcrypt(String password) {
		return password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
	}

}
