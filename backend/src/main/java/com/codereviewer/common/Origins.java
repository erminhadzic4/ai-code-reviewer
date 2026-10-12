package com.codereviewer.common;

import java.net.URI;
import java.util.Set;

/**
 * Checks for the configured addresses of external systems. Such an address is an origin
 * (scheme, host and optional port) and the only place the application sends that system's
 * credentials to, so it must be exactly that and must use https.
 */
public final class Origins {

	/** Plain HTTP is accepted only for these hosts, which is what the tests use. */
	private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1");

	private Origins() {
	}

	/**
	 * @param name the property the address comes from, for the error message
	 * @throws IllegalStateException if the address is not an https origin
	 */
	public static void requireOrigin(String name, URI url) {
		if (url == null || url.getHost() == null) {
			throw new IllegalStateException(name + " must be an absolute URL.");
		}
		boolean https = "https".equals(url.getScheme());
		boolean localHttp = "http".equals(url.getScheme()) && LOOPBACK_HOSTS.contains(url.getHost());
		if (!https && !localHttp) {
			throw new IllegalStateException(name + " must use https.");
		}
		// No path, query or user info.
		boolean hasPath = url.getRawPath() != null && !url.getRawPath().isEmpty();
		if (hasPath || url.getRawQuery() != null || url.getRawFragment() != null || url.getRawUserInfo() != null) {
			throw new IllegalStateException(name + " must contain only scheme, host and port.");
		}
	}

}
