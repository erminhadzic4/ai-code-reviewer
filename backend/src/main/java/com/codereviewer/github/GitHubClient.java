package com.codereviewer.github;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;

/**
 * Reads public repositories from GitHub.
 *
 * <p>Every address is built here from the configured API origin and from names that were
 * validated first; nothing a user typed is ever requested as a URL. Redirects are not
 * followed automatically: each one is checked against the two configured origins, and the
 * access token is sent to the API origin only.
 *
 * <p>Nothing is retried and nothing waits. A failure is reported as a
 * {@link GitHubException} that says whether trying again later can help.
 */
@Component
public class GitHubClient {

	private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);

	private static final String JSON = "application/vnd.github+json";

	/** Makes the commits endpoint answer with the bare SHA instead of the whole commit. */
	private static final String SHA = "application/vnd.github.sha";

	private static final String API_VERSION = "2022-11-28";

	/** GitHub rejects requests without a user agent. */
	private static final String USER_AGENT = "ai-code-reviewer";

	private static final int MAX_REDIRECTS = 3;

	private static final int MAX_BRANCH_LENGTH = 255;

	private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-f]{40}");

	private static final String UNAVAILABLE = "GitHub is not available right now";

	private static final String UNEXPECTED = "GitHub returned an unexpected answer";

	private final GitHubProperties properties;
	private final Clock clock;
	private final RestClient http;

	@Autowired
	public GitHubClient(GitHubProperties properties) {
		this(properties, Clock.systemUTC());
	}

	GitHubClient(GitHubProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
		HttpClient client = HttpClient.newBuilder()
				.connectTimeout(properties.connectTimeout())
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
		requestFactory.setReadTimeout(properties.readTimeout());
		this.http = RestClient.builder().requestFactory(requestFactory).build();
	}

	/**
	 * Finds the default branch of a repository and the latest commit on it. Two API requests.
	 *
	 * @throws GitHubException if the repository is missing, private, empty or too large, or
	 * GitHub cannot be used right now
	 */
	public RepoHead resolveHead(RepoRef repo) {
		RepositoryResponse repository = get(apiUri("/repos/" + repo.owner() + "/" + repo.name()), JSON,
				response -> response.bodyTo(RepositoryResponse.class));
		if (repository == null) {
			throw new GitHubException(GitHubErrorCode.GITHUB_UNAVAILABLE, UNEXPECTED);
		}
		if (repository.isPrivate()) {
			// Reachable only when the token has access. Private code must not reach a model.
			throw notFound();
		}
		// The name GitHub uses now: after a rename the old one only redirects.
		RepoRef current = currentName(repository.fullName());
		String branch = validBranch(repository.defaultBranch());
		if (repository.sizeInKilobytes() * 1024 > properties.maxRepoSize().toBytes()) {
			throw new GitHubException(GitHubErrorCode.REPO_TOO_LARGE, "Repository is too large to review");
		}
		String sha = get(
				apiUri("/repos/" + current.owner() + "/" + current.name() + "/commits/heads/" + encodeBranch(branch)),
				SHA, response -> new String(response.getBody().readNBytes(100), StandardCharsets.UTF_8).strip());
		if (!COMMIT_SHA.matcher(sha).matches()) {
			throw new GitHubException(GitHubErrorCode.GITHUB_UNAVAILABLE, UNEXPECTED);
		}
		return new RepoHead(current, branch, sha);
	}

	private URI apiUri(String path) {
		return URI.create(properties.apiUrl() + path);
	}

	// A branch name may contain "/", "#", "?" and "%". Each part between slashes is encoded
	// on its own, so the slashes stay path separators and nothing else can end or change
	// the path.
	private static String encodeBranch(String branch) {
		return Arrays.stream(branch.split("/", -1))
				.map(segment -> UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8))
				.collect(Collectors.joining("/"));
	}

	private static RepoRef currentName(String fullName) {
		try {
			return RepoUrlParser.parse("https://github.com/" + fullName);
		}
		catch (InvalidRepoUrlException ex) {
			throw new GitHubException(GitHubErrorCode.GITHUB_UNAVAILABLE, UNEXPECTED);
		}
	}

	private static String validBranch(String branch) {
		if (branch == null || branch.isBlank() || branch.length() > MAX_BRANCH_LENGTH
				|| branch.chars().anyMatch(Character::isISOControl)) {
			throw new GitHubException(GitHubErrorCode.GITHUB_UNAVAILABLE, UNEXPECTED);
		}
		return branch;
	}

	/**
	 * Sends one GET and hands a successful response to {@code reader}. Follows at most
	 * {@value #MAX_REDIRECTS} redirects, each only to a configured origin.
	 */
	private <T> T get(URI uri, String accept, ResponseReader<T> reader) {
		URI target = uri;
		for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
			Outcome<T> outcome = send(target, accept, reader);
			if (outcome.redirect() == null) {
				return outcome.value();
			}
			target = outcome.redirect();
		}
		log.warn("GitHub redirected more than {} times for {}", MAX_REDIRECTS, uri);
		throw new GitHubException(GitHubErrorCode.GITHUB_UNAVAILABLE, UNEXPECTED);
	}

	private <T> Outcome<T> send(URI target, String accept, ResponseReader<T> reader) {
		boolean toApi = sameOrigin(target, properties.apiUrl());
		try {
			return http.get().uri(target).headers(headers -> {
				headers.set(HttpHeaders.ACCEPT, accept);
				headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
				if (toApi) {
					headers.set("X-GitHub-Api-Version", API_VERSION);
					if (properties.hasToken()) {
						headers.setBearerAuth(properties.token());
					}
				}
			}).exchange((request, response) -> {
				HttpStatusCode status = response.getStatusCode();
				if (status.is2xxSuccessful()) {
					return new Outcome<>(reader.read(response), null);
				}
				if (status.is3xxRedirection()) {
					return new Outcome<>(null, redirectTarget(target, response.getHeaders()));
				}
				throw failure(status.value(), response.getHeaders());
			});
		}
		catch (RestClientException | HttpMessageConversionException ex) {
			// No connection, a timeout, a dropped connection or a body that cannot be read.
			log.warn("Request to GitHub failed for {}: {}", target, ex.toString());
			throw new GitHubException(GitHubErrorCode.GITHUB_UNAVAILABLE, UNAVAILABLE, ex);
		}
	}

	private URI redirectTarget(URI from, HttpHeaders headers) {
		String location = headers.getFirst(HttpHeaders.LOCATION);
		URI target;
		try {
			target = location == null ? null : from.resolve(location);
		}
		catch (IllegalArgumentException ex) {
			target = null;
		}
		if (target == null || !(sameOrigin(target, properties.apiUrl()) || sameOrigin(target, properties.archiveUrl()))) {
			log.warn("Refused a redirect from {} to {}", from, location);
			throw new GitHubException(GitHubErrorCode.GITHUB_UNAVAILABLE, UNEXPECTED);
		}
		return target;
	}

	private static boolean sameOrigin(URI uri, URI origin) {
		return uri.getScheme() != null && uri.getScheme().equalsIgnoreCase(origin.getScheme())
				&& uri.getHost() != null && uri.getHost().equalsIgnoreCase(origin.getHost())
				&& uri.getRawUserInfo() == null && port(uri) == port(origin);
	}

	private static int port(URI uri) {
		if (uri.getPort() != -1) {
			return uri.getPort();
		}
		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}

	private GitHubException failure(int status, HttpHeaders headers) {
		return switch (status) {
			case 404 -> notFound();
			case 409 -> new GitHubException(GitHubErrorCode.REPO_EMPTY, "Repository has no commits");
			case 401 -> {
				log.error("GitHub rejected the configured GITHUB_TOKEN. Replace it or remove it.");
				yield new GitHubException(GitHubErrorCode.GITHUB_AUTH_FAILED,
						"GitHub rejected the configured access token");
			}
			case 403, 429 -> rateLimitOrDenied(status, headers);
			case 451 -> new GitHubException(GitHubErrorCode.REPO_UNAVAILABLE,
					"GitHub does not serve this repository");
			default -> {
				log.warn("GitHub answered with status {}", status);
				yield new GitHubException(GitHubErrorCode.GITHUB_UNAVAILABLE, UNAVAILABLE);
			}
		};
	}

	// GitHub uses 403 and 429 for both of its limits. The primary one sets
	// x-ratelimit-remaining to 0 and gives the reset time; the secondary one sends
	// retry-after. A 403 with neither is a real refusal.
	private GitHubException rateLimitOrDenied(int status, HttpHeaders headers) {
		String message = "GitHub rate limit reached. Try again later";
		if ("0".equals(headers.getFirst("x-ratelimit-remaining"))) {
			Long reset = parseSeconds(headers.getFirst("x-ratelimit-reset"));
			return new GitHubException(GitHubErrorCode.RATE_LIMITED, message,
					reset == null ? null : Instant.ofEpochSecond(reset));
		}
		Long retryAfter = parseSeconds(headers.getFirst(HttpHeaders.RETRY_AFTER));
		if (retryAfter != null) {
			return new GitHubException(GitHubErrorCode.RATE_LIMITED, message, clock.instant().plusSeconds(retryAfter));
		}
		if (status == 429) {
			return new GitHubException(GitHubErrorCode.RATE_LIMITED, message);
		}
		return new GitHubException(GitHubErrorCode.REPO_UNAVAILABLE, "GitHub does not serve this repository");
	}

	private static Long parseSeconds(String value) {
		if (value == null || !value.strip().matches("\\d{1,12}")) {
			return null;
		}
		return Long.parseLong(value.strip());
	}

	private static GitHubException notFound() {
		return new GitHubException(GitHubErrorCode.REPO_NOT_FOUND, "Repository not found or private");
	}

	@FunctionalInterface
	private interface ResponseReader<T> {

		T read(ConvertibleClientHttpResponse response) throws IOException;

	}

	/** Either the value read from a successful response or the address to try next. */
	private record Outcome<T>(T value, URI redirect) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record RepositoryResponse(@JsonProperty("full_name") String fullName,
			@JsonProperty("default_branch") String defaultBranch, @JsonProperty("size") long sizeInKilobytes,
			@JsonProperty("private") boolean isPrivate) {
	}

}
