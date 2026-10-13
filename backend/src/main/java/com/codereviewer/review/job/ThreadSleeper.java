package com.codereviewer.review.job;

import java.time.Duration;

import org.springframework.stereotype.Component;

/** Waits by putting the calling thread to sleep. */
@Component
public class ThreadSleeper implements Sleeper {

	@Override
	public void sleep(Duration duration) throws InterruptedException {
		Thread.sleep(duration);
	}

}
