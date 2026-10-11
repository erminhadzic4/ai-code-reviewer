package com.codereviewer.github;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the owner and the repository name out of a URL typed by a user.
 *
 * <p>The URL itself is never requested. Only the two names leave this class, and they are
 * limited to characters that cannot change the host or the path of an address built from
 * them. The whole input must match one pattern, so anything that is not exactly
 * {@code github.com/owner/repository} is rejected instead of being interpreted.
 */
public final class RepoUrlParser {

	/** Longer input cannot be a valid URL: the longest accepted one is well below this. */
	static final int MAX_URL_LENGTH = 200;

	/** GitHub's own limit for repository names. */
	static final int MAX_NAME_LENGTH = 100;

	// Owner: 1 to 39 letters, digits or hyphens, with no hyphen at either end.
	// Name: letters, digits, dot, underscore and hyphen; one ".git" suffix is dropped.
	private static final Pattern REPO_URL = Pattern.compile(
			"(?:https?://)?(?:www\\.)?github\\.com"
					+ "/([a-z0-9](?:[a-z0-9-]{0,37}[a-z0-9])?)"
					+ "/([a-z0-9._-]+?)(?:\\.git)?/?",
			Pattern.CASE_INSENSITIVE);

	private RepoUrlParser() {
	}

	public static RepoRef parse(String url) {
		if (url == null) {
			throw new InvalidRepoUrlException();
		}
		String candidate = url.strip();
		if (candidate.length() > MAX_URL_LENGTH) {
			throw new InvalidRepoUrlException();
		}
		Matcher matcher = REPO_URL.matcher(candidate);
		if (!matcher.matches()) {
			throw new InvalidRepoUrlException();
		}
		String name = matcher.group(2);
		if (name.length() > MAX_NAME_LENGTH || name.equals(".") || name.equals("..")) {
			throw new InvalidRepoUrlException();
		}
		return new RepoRef(matcher.group(1).toLowerCase(Locale.ROOT), name.toLowerCase(Locale.ROOT));
	}

}
