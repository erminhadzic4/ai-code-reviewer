package com.codereviewer.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The clock of the review job. It is a bean so that a test of the whole application can
 * replace it and move time forward instead of waiting.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
