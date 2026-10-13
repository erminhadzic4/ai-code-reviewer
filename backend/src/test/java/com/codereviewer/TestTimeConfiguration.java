package com.codereviewer;

import com.codereviewer.testsupport.MutableClock;
import com.codereviewer.testsupport.RecordingSleeper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the clock and the sleeper of the application, so that a test moves time forward
 * instead of waiting for it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestTimeConfiguration {

	@Bean
	@Primary
	MutableClock testClock() {
		return new MutableClock();
	}

	@Bean
	@Primary
	RecordingSleeper testSleeper(MutableClock clock) {
		return new RecordingSleeper(clock);
	}

}
