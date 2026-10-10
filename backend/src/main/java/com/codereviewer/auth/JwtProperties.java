package com.codereviewer.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for signing access tokens. Validated when the application starts, so a missing
 * or weak secret stops the startup instead of failing at the first login.
 *
 * @param secret signing secret, used as UTF-8 bytes
 * @param expiration how long a token stays valid
 */
@ConfigurationProperties("app.jwt")
public record JwtProperties(String secret, Duration expiration) {

	/** HS256 needs a key of at least 256 bits. */
	static final int MIN_SECRET_BYTES = 32;

	/** The value shipped in .env.example. It is public, so it must never sign a token. */
	static final String EXAMPLE_SECRET = "change-me-to-a-long-random-string";

	public JwtProperties {
		if (secret == null || secret.isBlank()) {
			throw new IllegalStateException("JWT_SECRET is not set. Copy .env.example to .env and set it.");
		}
		if (secret.equals(EXAMPLE_SECRET)) {
			throw new IllegalStateException("JWT_SECRET still has the example value. Generate your own.");
		}
		if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
			throw new IllegalStateException("JWT_SECRET must be at least 32 bytes (256 bits) for HS256.");
		}
		if (expiration == null || expiration.isZero() || expiration.isNegative()) {
			throw new IllegalStateException("app.jwt.expiration must be a positive duration.");
		}
	}

	byte[] secretBytes() {
		return secret.getBytes(StandardCharsets.UTF_8);
	}

}
