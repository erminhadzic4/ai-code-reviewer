package com.codereviewer.auth;

import com.codereviewer.auth.dto.AuthResponse;
import com.codereviewer.auth.dto.LoginRequest;
import com.codereviewer.auth.dto.RegisterRequest;
import com.codereviewer.auth.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create an account and get a token")
	@SecurityRequirements
	@ApiResponse(responseCode = "201", description = "Account created")
	@ApiResponse(responseCode = "400", description = "Invalid email or password")
	@ApiResponse(responseCode = "409", description = "Email already registered")
	AuthResponse register(@Valid @RequestBody RegisterRequest request) {
		return authService.register(request);
	}

	@PostMapping("/login")
	@Operation(summary = "Log in and get a token")
	@SecurityRequirements
	@ApiResponse(responseCode = "200", description = "Logged in")
	@ApiResponse(responseCode = "400", description = "Empty field")
	@ApiResponse(responseCode = "401", description = "Wrong email or password")
	AuthResponse login(@Valid @RequestBody LoginRequest request) {
		return authService.login(request);
	}

	@GetMapping("/me")
	@Operation(summary = "The signed-in user")
	@ApiResponse(responseCode = "200", description = "The user")
	@ApiResponse(responseCode = "401", description = "No valid token")
	UserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
		return authService.currentUser(principal);
	}

}
