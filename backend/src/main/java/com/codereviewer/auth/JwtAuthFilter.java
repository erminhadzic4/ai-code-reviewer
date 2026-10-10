package com.codereviewer.auth;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns a valid {@code Authorization: Bearer <token>} header into an authenticated request.
 * It never rejects a request itself: without a valid token the request simply stays
 * anonymous, and the access rules decide whether that is allowed for the path.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

	private static final String PREFIX = "Bearer ";

	private final JwtService jwtService;

	public JwtAuthFilter(JwtService jwtService) {
		this.jwtService = jwtService;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header != null && header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
			jwtService.parse(header.substring(PREFIX.length()).trim()).ifPresent(this::authenticate);
		}
		chain.doFilter(request, response);
	}

	private void authenticate(long userId) {
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated(new AuthenticatedUser(userId), null, List.of()));
		SecurityContextHolder.setContext(context);
	}

}
