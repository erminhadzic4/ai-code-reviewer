package com.codereviewer.github;

import java.net.URI;
import java.time.Duration;

import org.springframework.util.unit.DataSize;

/** Settings for tests: the shipped defaults, with any of them replaced. */
final class TestGitHubProperties {

	String token;
	URI apiUrl = URI.create("https://api.github.com");
	URI archiveUrl = URI.create("https://codeload.github.com");
	Duration connectTimeout = Duration.ofSeconds(5);
	Duration readTimeout = Duration.ofSeconds(30);
	Duration downloadTimeout = Duration.ofSeconds(60);
	DataSize maxRepoSize = DataSize.ofGigabytes(1);
	DataSize maxDownloadSize = DataSize.ofMegabytes(50);
	DataSize maxUncompressedSize = DataSize.ofMegabytes(200);
	int maxEntries = 20_000;
	DataSize maxFileSize = DataSize.ofKilobytes(200);
	int maxFiles = 500;
	DataSize maxSourceSize = DataSize.ofMegabytes(2);

	GitHubProperties build() {
		return new GitHubProperties(token, apiUrl, archiveUrl, connectTimeout, readTimeout, downloadTimeout,
				maxRepoSize, maxDownloadSize, maxUncompressedSize, maxEntries, maxFileSize, maxFiles, maxSourceSize);
	}

}
