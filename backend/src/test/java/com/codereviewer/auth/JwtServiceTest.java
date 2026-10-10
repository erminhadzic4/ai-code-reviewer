package com.codereviewer.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;

import javax.crypto.spec.SecretKeySpec;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JwtServiceTest {

	private static final String SECRET = "unit-test-secret-0123456789-abcdefghij";
	private static final String OTHER_SECRET = "another-secret-0123456789-abcdefghijkl";
	private static final Duration EXPIRATION = Duration.ofHours(24);
	private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

	private final JwtService jwtService = serviceAt(SECRET, NOW);

	@Test
	void issuedTokenIsReadBackAsTheSameUserId() {
		String token = jwtService.issue(42L);

		assertThat(jwtService.parse(token)).contains(42L);
	}

	@Test
	void tokenCarriesOnlySubjectIssuedAtAndExpiry() {
		String token = jwtService.issue(42L);

		String payload = decodeSegment(token, 1);
		long issuedAt = NOW.getEpochSecond();
		assertThat(payload).isEqualTo("{\"sub\":\"42\",\"iat\":" + issuedAt + ",\"exp\":"
				+ (issuedAt + EXPIRATION.toSeconds()) + "}");
		assertThat(decodeSegment(token, 0)).contains("\"alg\":\"HS256\"");
	}

	@Test
	void tokenIsValidUntilItExpires() {
		String token = jwtService.issue(42L);

		assertThat(serviceAt(SECRET, NOW.plus(EXPIRATION).minusSeconds(1)).parse(token)).contains(42L);
		assertThat(serviceAt(SECRET, NOW.plus(EXPIRATION).plusSeconds(1)).parse(token)).isEmpty();
	}

	@Test
	void tokenWithAModifiedPayloadIsRejected() {
		String[] parts = jwtService.issue(42L).split("\\.");
		String forgedPayload = encode(decodeSegment(parts, 1).replace("\"42\"", "\"43\""));

		assertThat(jwtService.parse(parts[0] + "." + forgedPayload + "." + parts[2])).isEmpty();
	}

	@Test
	void tokenSignedWithAnotherSecretIsRejected() {
		String token = serviceAt(OTHER_SECRET, NOW).issue(42L);

		assertThat(jwtService.parse(token)).isEmpty();
	}

	@Test
	void unsignedTokenIsRejected() {
		String header = encode("{\"alg\":\"none\"}");
		String payload = encode("{\"sub\":\"42\",\"exp\":" + NOW.plusSeconds(3600).getEpochSecond() + "}");

		assertThat(jwtService.parse(header + "." + payload + ".")).isEmpty();
	}

	@Test
	void tokenSignedWithAnotherHmacAlgorithmIsRejected() {
		// 64 bytes, long enough for HS512, so only the algorithm differs from a valid token.
		String longSecret = SECRET + "-0123456789-abcdefghijklmn";
		byte[] keyBytes = longSecret.getBytes(StandardCharsets.UTF_8);
		String token = Jwts.builder()
				.subject("42")
				.expiration(Date.from(NOW.plusSeconds(3600)))
				.signWith(new SecretKeySpec(keyBytes, "HmacSHA512"), Jwts.SIG.HS512)
				.compact();

		assertThat(serviceAt(longSecret, NOW).parse(token)).isEmpty();
	}

	@Test
	void tokenWithANonNumericSubjectIsRejected() {
		String token = Jwts.builder()
				.subject("not-a-number")
				.expiration(Date.from(NOW.plusSeconds(3600)))
				.signWith(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"), Jwts.SIG.HS256)
				.compact();

		assertThat(jwtService.parse(token)).isEmpty();
	}

	@Test
	void tokenWithoutASubjectIsRejected() {
		String token = Jwts.builder()
				.expiration(Date.from(NOW.plusSeconds(3600)))
				.signWith(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"), Jwts.SIG.HS256)
				.compact();

		assertThat(jwtService.parse(token)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "garbage", "a.b.c", "eyJhbGciOiJIUzI1NiJ9" })
	void malformedTokenIsRejected(String token) {
		assertThat(jwtService.parse(token)).isEmpty();
	}

	private static JwtService serviceAt(String secret, Instant instant) {
		return new JwtService(new JwtProperties(secret, EXPIRATION), Clock.fixed(instant, ZoneOffset.UTC));
	}

	private static String decodeSegment(String token, int index) {
		return decodeSegment(token.split("\\."), index);
	}

	private static String decodeSegment(String[] parts, int index) {
		return new String(Base64.getUrlDecoder().decode(parts[index]), StandardCharsets.UTF_8);
	}

	private static String encode(String json) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

}
