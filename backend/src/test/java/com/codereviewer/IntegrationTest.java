package com.codereviewer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full application context with MockMvc and a real PostgreSQL in a container.
 * Every test using this annotation shares one context, and therefore one container.
 * The clock and the sleeper are test doubles: time passes only when a test moves it.
 * GitHub and the provider are replaced as well, see {@link TestReviewConfiguration}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
// Set here and not in a file, so that they win over a developer's .env: the tests need the
// fake provider, and a real GitHub token must never be sent to the server that plays GitHub.
@SpringBootTest(properties = { "app.review.provider=fake", "app.github.token=" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestTimeConfiguration.class, TestReviewConfiguration.class })
@ActiveProfiles("test")
public @interface IntegrationTest {
}
