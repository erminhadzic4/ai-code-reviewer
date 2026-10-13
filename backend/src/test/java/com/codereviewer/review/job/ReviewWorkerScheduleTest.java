package com.codereviewer.review.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The timer itself, with a worker that does nothing. These are the only tests that let real
 * time pass until something happens; each is bounded and normally takes a few milliseconds.
 */
class ReviewWorkerScheduleTest {

	private final ReviewWorker worker = mock(ReviewWorker.class);

	// Loads the real application.yml, so the shipped settings are what switches the timer on.
	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withBean(ReviewWorker.class, () -> worker)
			.withUserConfiguration(ReviewWorkerSchedule.class);

	@Test
	void workerIsCalledSoonAfterTheApplicationStartsWithTheShippedSettings() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(ReviewWorkerSchedule.class);
			verify(worker, timeout(5000).atLeastOnce()).tick();
		});
	}

	@Test
	void workerIsCalledAgainAfterEveryPause() {
		contextRunner.withPropertyValues("app.review.worker.poll-interval=10ms")
				.run(context -> verify(worker, timeout(5000).atLeast(3)).tick());
	}

	@Test
	void switchedOffThereIsNoTimerAndTheWorkerIsNeverCalled() {
		contextRunner.withPropertyValues("app.review.worker.enabled=false", "app.review.worker.poll-interval=10ms")
				.run(context -> {
					assertThat(context).hasNotFailed().doesNotHaveBean(ReviewWorkerSchedule.class);
					assertThat(context).hasSingleBean(ReviewWorker.class);
					// Ten pauses long: with a timer the worker would have been called by now.
					Thread.sleep(100);
					verifyNoInteractions(worker);
				});
	}

}
