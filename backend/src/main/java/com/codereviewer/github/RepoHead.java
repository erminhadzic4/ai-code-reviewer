package com.codereviewer.github;

/**
 * The latest commit on a repository's default branch.
 *
 * @param repo the repository under the name GitHub currently uses for it
 * @param defaultBranch name of the default branch
 * @param commitSha full 40-character SHA of the latest commit on that branch
 */
public record RepoHead(RepoRef repo, String defaultBranch, String commitSha) {
}
