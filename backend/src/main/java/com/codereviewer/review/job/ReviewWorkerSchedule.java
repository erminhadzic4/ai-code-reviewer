package com.codereviewer.review.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The timer behind the worker. It is separate from {@link ReviewWorker} so that it can be
 * switched off: tests keep the worker and call it themselves, on their own thread.
 *
 * <p>The scheduler has a single thread and the delay is counted from the end of a round, so
 * two rounds never overlap and one review runs at a time.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.review.worker.enabled", havingValue = "true")
public class ReviewWorkerSchedule {

	private final ReviewWorker worker;

	public ReviewWorkerSchedule(ReviewWorker worker) {
		this.worker = worker;
	}

	@Scheduled(fixedDelayString = "${app.review.worker.poll-interval}")
	void tick() {
		worker.tick();
	}

}
