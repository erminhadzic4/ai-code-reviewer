package com.codereviewer.github;

/**
 * A GitHub repository, identified by owner and name. Both are lower-case, because GitHub
 * treats them as case-insensitive.
 */
public record RepoRef(String owner, String name) {
}
