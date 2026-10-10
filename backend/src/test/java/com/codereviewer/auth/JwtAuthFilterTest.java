package com.codereviewer.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthFilterTest {

	private static final JwtProperties PROPERTIES = new JwtProperties("unit-test-secret-0123456789-abcdefghij",
			Duration.ofHours(24));

	private final JwtService jwtService = new JwtService(PROPERTIES);
	private final JwtAuthFilter filter = new JwtAuthFilter(jwtService);

	private final MockHttpServletRequest request = new MockHttpServletRequest();
	private final MockHttpServletResponse response = new MockHttpServletResponse();
	private final MockFilterChain chain = new MockFilterChain();

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void requestWithoutAHeaderStaysAnonymous() throws Exception {
		filter.doFilter(request, response, chain);

		assertStaysAnonymousAndContinues();
	}

	@Test
	void otherAuthenticationSchemeIsIgnored() throws Exception {
		request.addHeader("Authorization", "Basic dXNlcjpwYXNzd29yZA==");

		filter.doFilter(request, response, chain);

		assertStaysAnonymousAndContinues();
	}

	@Test
	void validTokenAuthenticatesTheRequestWithTheUserId() throws Exception {
		request.addHeader("Authorization", "Bearer " + jwtService.issue(42L));

		filter.doFilter(request, response, chain);

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		assertThat(authentication).isNotNull();
		assertThat(authentication.isAuthenticated()).isTrue();
		assertThat(authentication.getPrincipal()).isEqualTo(new AuthenticatedUser(42L));
		assertThat(authentication.getAuthorities()).isEmpty();
		assertThat(chain.getRequest()).isSameAs(request);
	}

	@Test
	void schemeNameIsNotCaseSensitive() throws Exception {
		request.addHeader("Authorization", "bearer " + jwtService.issue(42L));

		filter.doFilter(request, response, chain);

		assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
				.isEqualTo(new AuthenticatedUser(42L));
	}

	@Test
	void invalidTokenStaysAnonymous() throws Exception {
		request.addHeader("Authorization", "Bearer not-a-token");

		filter.doFilter(request, response, chain);

		assertStaysAnonymousAndContinues();
	}

	@Test
	void emptyTokenStaysAnonymous() throws Exception {
		request.addHeader("Authorization", "Bearer ");

		filter.doFilter(request, response, chain);

		assertStaysAnonymousAndContinues();
	}

	@Test
	void expiredTokenStaysAnonymous() throws Exception {
		Clock twoDaysAgo = Clock.fixed(Instant.now().minus(Duration.ofDays(2)), ZoneOffset.UTC);
		request.addHeader("Authorization", "Bearer " + new JwtService(PROPERTIES, twoDaysAgo).issue(42L));

		filter.doFilter(request, response, chain);

		assertStaysAnonymousAndContinues();
	}

	// The filter never answers the request itself; that is left to the access rules.
	private void assertStaysAnonymousAndContinues() {
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		assertThat(chain.getRequest()).isSameAs(request);
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.isCommitted()).isFalse();
	}

}
