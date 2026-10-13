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
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestTimeConfiguration.class })
@ActiveProfiles("test")
public @interface IntegrationTest {
}
