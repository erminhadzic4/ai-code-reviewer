package com.codereviewer.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codereviewer.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class UserSchemaIntegrationTest {

	private static final String INSERT = "INSERT INTO users (email, password_hash) VALUES (?, ?)";

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private UserRepository userRepository;

	@Test
	void savedUserGetsAnIdAndCanBeFoundByEmail() {
		User saved = userRepository.saveAndFlush(new User("schema-roundtrip@example.com", "hash"));

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getCreatedAt()).isNotNull();
		assertThat(userRepository.findByEmail("schema-roundtrip@example.com")).contains(saved);
		assertThat(userRepository.existsByEmail("schema-roundtrip@example.com")).isTrue();
		assertThat(userRepository.existsByEmail("nobody@example.com")).isFalse();
	}

	@Test
	void databaseRejectsAnEmailThatIsNotLowerCase() {
		assertThatThrownBy(() -> jdbcTemplate.update(INSERT, "Schema-Upper@Example.com", "hash"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_users_email_lowercase");
	}

	@Test
	void databaseRejectsADuplicateEmail() {
		jdbcTemplate.update(INSERT, "schema-duplicate@example.com", "hash");

		assertThatThrownBy(() -> jdbcTemplate.update(INSERT, "schema-duplicate@example.com", "other"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uq_users_email");
	}

}
