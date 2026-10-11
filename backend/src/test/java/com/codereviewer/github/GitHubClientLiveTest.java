package com.codereviewer.github;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The only test that talks to the real GitHub. It is skipped unless GITHUB_LIVE_TEST=true
 * is set, which CI never does: every other test uses a local stand-in. Run it by hand to
 * check that the stand-in still matches the real API. It costs three API requests; set
 * GITHUB_TOKEN to use the higher rate limit.
 */
@EnabledIfEnvironmentVariable(named = "GITHUB_LIVE_TEST", matches = "true")
class GitHubClientLiveTest {

	@Test
	void readsASmallPublicRepository() {
		TestGitHubProperties settings = new TestGitHubProperties();
		settings.token = System.getenv("GITHUB_TOKEN");
		GitHubClient client = new GitHubClient(settings.build());
		RepoRef repo = RepoUrlParser.parse("https://github.com/octocat/Hello-World");

		RepoHead head = client.resolveHead(repo);
		RepoSnapshot snapshot = client.fetchSnapshot(head);

		assertThat(head.repo()).isEqualTo(new RepoRef("octocat", "hello-world"));
		assertThat(head.defaultBranch()).isNotBlank();
		assertThat(head.commitSha()).matches("[0-9a-f]{40}");
		assertThat(snapshot.head()).isEqualTo(head);
		int skipped = snapshot.skipped().values().stream().mapToInt(Integer::intValue).sum();
		assertThat(snapshot.files().size() + skipped).isPositive();
	}

}
