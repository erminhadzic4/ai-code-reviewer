package com.codereviewer.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class RepoUrlParserTest {

	private static final RepoRef SPRING_BOOT = new RepoRef("spring-projects", "spring-boot");

	@ParameterizedTest
	@ValueSource(strings = {
			"https://github.com/spring-projects/spring-boot",
			"https://github.com/spring-projects/spring-boot/",
			"https://github.com/spring-projects/spring-boot.git",
			"https://github.com/spring-projects/spring-boot.git/",
			"http://github.com/spring-projects/spring-boot",
			"https://www.github.com/spring-projects/spring-boot",
			"github.com/spring-projects/spring-boot",
			"www.github.com/spring-projects/spring-boot",
			"HTTPS://GitHub.com/Spring-Projects/Spring-Boot",
			"  https://github.com/spring-projects/spring-boot  " })
	void acceptedFormsGiveTheSameRepository(String url) {
		assertThat(RepoUrlParser.parse(url)).isEqualTo(SPRING_BOOT);
	}

	@Test
	void nameMayContainDotsAndUnderscores() {
		assertThat(RepoUrlParser.parse("https://github.com/a/next.js")).isEqualTo(new RepoRef("a", "next.js"));
		assertThat(RepoUrlParser.parse("https://github.com/a/my_repo.v2")).isEqualTo(new RepoRef("a", "my_repo.v2"));
	}

	@Test
	void onlyOneGitSuffixIsDropped() {
		assertThat(RepoUrlParser.parse("https://github.com/a/repo.git.git")).isEqualTo(new RepoRef("a", "repo.git"));
	}

	@Test
	void longestOwnerAndNameAreAccepted() {
		String owner = "a".repeat(39);
		String name = "b".repeat(RepoUrlParser.MAX_NAME_LENGTH);

		assertThat(RepoUrlParser.parse("https://github.com/" + owner + "/" + name))
				.isEqualTo(new RepoRef(owner, name));
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {
			"",
			"   ",
			"spring-projects/spring-boot",
			"https://github.com",
			"https://github.com/",
			"https://github.com/spring-projects",
			"https://github.com/spring-projects/",
			// Other hosts, including ones that only look like github.com.
			"https://gitlab.com/owner/repo",
			"https://github.com.evil.com/owner/repo",
			"https://evilgithub.com/owner/repo",
			"https://gist.github.com/owner/repo",
			"https://api.github.com/owner/repo",
			"https://raw.githubusercontent.com/owner/repo",
			"https://127.0.0.1/owner/repo",
			"https://localhost/owner/repo",
			// The real host hidden in the user-info or path part.
			"https://github.com@evil.com/owner/repo",
			"https://user:pass@github.com/owner/repo",
			"https://evil.com/github.com/owner/repo",
			"https://evil.com#github.com/owner/repo",
			"https://github.com:443/owner/repo",
			"https://github.com\\owner\\repo",
			"https://github.com//owner/repo",
			// Other schemes and the SSH form.
			"ftp://github.com/owner/repo",
			"file://github.com/owner/repo",
			"git@github.com:owner/repo.git",
			"ssh://git@github.com/owner/repo",
			// Anything after the repository name.
			"https://github.com/owner/repo/tree/main",
			"https://github.com/owner/repo/blob/main/README.md",
			"https://github.com/owner/repo/pull/1",
			"https://github.com/owner/repo?tab=readme",
			"https://github.com/owner/repo#readme",
			"https://github.com/owner/repo//",
			// Names GitHub does not allow, or that would change the path.
			"https://github.com/-owner/repo",
			"https://github.com/owner-/repo",
			"https://github.com/own_er/repo",
			"https://github.com/owner/.",
			"https://github.com/owner/..",
			"https://github.com/../repo",
			"https://github.com/owner/re po",
			"https://github.com/owner/repo%2Fx",
			"https://github.com/owner/repo\u0000",
			"https://github.com/owner/répo",
			"https://github.com/owner/repo\nhttps://evil.com" })
	void everythingElseIsRejected(String url) {
		assertThatThrownBy(() -> RepoUrlParser.parse(url)).isInstanceOf(InvalidRepoUrlException.class);
	}

	@Test
	void ownerLongerThan39CharactersIsRejected() {
		String url = "https://github.com/" + "a".repeat(40) + "/repo";

		assertThatThrownBy(() -> RepoUrlParser.parse(url)).isInstanceOf(InvalidRepoUrlException.class);
	}

	@Test
	void nameLongerThan100CharactersIsRejected() {
		String url = "https://github.com/a/" + "b".repeat(RepoUrlParser.MAX_NAME_LENGTH + 1);

		assertThatThrownBy(() -> RepoUrlParser.parse(url)).isInstanceOf(InvalidRepoUrlException.class);
	}

	@Test
	void overlongInputIsRejectedBeforeMatching() {
		String url = "https://github.com/a/b" + " ".repeat(10) + "x".repeat(RepoUrlParser.MAX_URL_LENGTH);

		assertThatThrownBy(() -> RepoUrlParser.parse(url)).isInstanceOf(InvalidRepoUrlException.class);
	}

	@Test
	void rejectionIsABadRequestThatSaysWhatIsExpected() {
		assertThatThrownBy(() -> RepoUrlParser.parse("https://gitlab.com/owner/repo"))
				.isInstanceOfSatisfying(InvalidRepoUrlException.class, ex -> {
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
					assertThat(ex.getMessage()).contains("https://github.com/owner/repository");
				});
	}

}
