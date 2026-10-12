package com.codereviewer.github;

import java.net.URI;
import java.time.Duration;

import com.codereviewer.common.Origins;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Settings for reading repositories from GitHub. Validated when the application starts.
 *
 * <p>The two addresses are the only hosts the client ever talks to. They come from the
 * configuration, never from a request.
 *
 * @param token optional personal access token; without it GitHub allows 60 requests an hour
 * @param apiUrl origin of the REST API
 * @param archiveUrl origin the API redirects to for archive downloads
 * @param connectTimeout time allowed to open a connection
 * @param readTimeout time allowed for a whole API request, from sending it to reading its answer
 * @param downloadTimeout time allowed for a whole archive download. Nothing limits a pause
 * inside it, so a server that stops sending holds the connection until this runs out
 * @param maxRepoSize repository size reported by GitHub above which no download is attempted.
 * That number covers the whole history and is refreshed only now and then, so this is a
 * coarse guard only; the limits below are the real ones
 * @param maxDownloadSize compressed bytes of an archive
 * @param maxUncompressedSize bytes inflated from an archive, skipped entries included
 * @param maxEntries entries in an archive
 * @param maxFileSize size above which a single file is skipped
 * @param maxFiles files kept for review
 * @param maxSourceSize total size of the files kept for review
 */
@ConfigurationProperties("app.github")
public record GitHubProperties(String token, URI apiUrl, URI archiveUrl, Duration connectTimeout,
		Duration readTimeout, Duration downloadTimeout, DataSize maxRepoSize, DataSize maxDownloadSize,
		DataSize maxUncompressedSize, int maxEntries, DataSize maxFileSize, int maxFiles, DataSize maxSourceSize) {

	public GitHubProperties {
		token = (token == null || token.isBlank()) ? null : token.strip();
		Origins.requireOrigin("app.github.api-url", apiUrl);
		Origins.requireOrigin("app.github.archive-url", archiveUrl);
		requirePositive("app.github.connect-timeout", connectTimeout);
		requirePositive("app.github.read-timeout", readTimeout);
		requirePositive("app.github.download-timeout", downloadTimeout);
		requirePositive("app.github.max-repo-size", maxRepoSize);
		requirePositive("app.github.max-download-size", maxDownloadSize);
		requirePositive("app.github.max-uncompressed-size", maxUncompressedSize);
		requirePositive("app.github.max-entries", maxEntries);
		requirePositive("app.github.max-file-size", maxFileSize);
		requirePositive("app.github.max-files", maxFiles);
		requirePositive("app.github.max-source-size", maxSourceSize);
		// A file is read into one array, so its limit must fit an int with room to spare.
		if (maxFileSize.toBytes() > Integer.MAX_VALUE / 2) {
			throw new IllegalStateException("app.github.max-file-size must be below 1 GB.");
		}
	}

	public boolean hasToken() {
		return token != null;
	}

	// The generated toString would print the token.
	@Override
	public String toString() {
		return "GitHubProperties[apiUrl=" + apiUrl + ", archiveUrl=" + archiveUrl + ", token="
				+ (hasToken() ? "set" : "none") + "]";
	}

	private static void requirePositive(String name, Duration value) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalStateException(name + " must be a positive duration.");
		}
	}

	private static void requirePositive(String name, DataSize value) {
		if (value == null || value.toBytes() <= 0) {
			throw new IllegalStateException(name + " must be a positive size.");
		}
	}

	private static void requirePositive(String name, int value) {
		if (value <= 0) {
			throw new IllegalStateException(name + " must be positive.");
		}
	}

}
