package com.codereviewer.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Issues and reads access tokens: HS256, with the user id as the subject.
 */
@Service
public class JwtService {

	private static final String ALGORITHM = "HS256";

	private final SecretKey key;
	private final Duration expiration;
	private final Clock clock;
	private final JwtParser parser;

	@Autowired
	public JwtService(JwtProperties properties) {
		this(properties, Clock.systemUTC());
	}

	JwtService(JwtProperties properties, Clock clock) {
		this.key = new SecretKeySpec(properties.secretBytes(), "HmacSHA256");
		this.expiration = properties.expiration();
		this.clock = clock;
		this.parser = Jwts.parser()
				.verifyWith(key)
				.clock(() -> Date.from(clock.instant()))
				.build();
	}

	public String issue(long userId) {
		Instant now = clock.instant();
		return Jwts.builder()
				.subject(Long.toString(userId))
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(expiration)))
				.signWith(key, Jwts.SIG.HS256)
				.compact();
	}

	/**
	 * @return the user id, or empty when the token is malformed, expired, signed with
	 * another key or not signed with HS256
	 */
	public Optional<Long> parse(String token) {
		try {
			Jws<Claims> jws = parser.parseSignedClaims(token);
			if (!ALGORITHM.equals(jws.getHeader().getAlgorithm())) {
				return Optional.empty();
			}
			return Optional.of(Long.parseLong(jws.getPayload().getSubject()));
		}
		catch (JwtException | IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	public Duration expiration() {
		return expiration;
	}

}
