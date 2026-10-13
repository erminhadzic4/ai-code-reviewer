package com.codereviewer.testsupport;

import com.codereviewer.github.RepoRef;
import com.codereviewer.testsupport.FakeHttpServer.Response;

/** Programs a {@link FakeHttpServer} to answer like GitHub does for one repository. */
public final class GitHubStubs {

	public static final String BRANCH = "main";

	private GitHubStubs() {
	}

	/** A public repository whose default branch is at the given commit, with its archive. */
	public static void repository(FakeHttpServer github, RepoRef repo, String commitSha, byte[] archive) {
		renamed(github, repo, repo, commitSha, archive);
	}

	/** A repository that is asked for under one name and that GitHub reports under another. */
	public static void renamed(FakeHttpServer github, RepoRef requested, RepoRef current, String commitSha,
			byte[] archive) {
		github.on(repositoryPath(requested), Response.json(200, """
				{"id": 42, "full_name": "%s/%s", "default_branch": "%s", "size": 120, "private": false}
				""".formatted(current.owner(), current.name(), BRANCH)))
				.on(repositoryPath(current) + "/commits/heads/" + BRANCH, Response.text(200, commitSha))
				.on(zipballPath(current, commitSha), Response.zip(archive));
	}

	public static String repositoryPath(RepoRef repo) {
		return "/repos/" + repo.owner() + "/" + repo.name();
	}

	public static String zipballPath(RepoRef repo, String commitSha) {
		return repositoryPath(repo) + "/zipball/" + commitSha;
	}

}
