package com.codereviewer;

import com.codereviewer.provider.FakeReviewProvider;
import com.codereviewer.testsupport.FailingReviewProvider;
import com.codereviewer.testsupport.FakeHttpServer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Replaces the two external systems of a review: GitHub is a server on the loopback
 * interface that a test programs, and the provider is the fake one behind a wrapper that can
 * be told to fail. No test of the whole application reaches the network.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestReviewConfiguration {

	@Bean
	FakeHttpServer fakeGitHub() {
		return new FakeHttpServer();
	}

	// The port is known only once the server runs, so the addresses cannot be in a file.
	@Bean
	DynamicPropertyRegistrar gitHubAddresses(FakeHttpServer fakeGitHub) {
		return registry -> {
			registry.add("app.github.api-url", () -> fakeGitHub.origin().toString());
			registry.add("app.github.archive-url", () -> fakeGitHub.origin().toString());
		};
	}

	@Bean
	@Primary
	FailingReviewProvider testReviewProvider(FakeReviewProvider fake) {
		return new FailingReviewProvider(fake);
	}

}
