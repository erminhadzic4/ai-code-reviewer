package com.codereviewer.review.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import com.codereviewer.github.GitHubErrorCode;
import com.codereviewer.github.GitHubException;
import com.codereviewer.github.RepoRef;
import com.codereviewer.review.Review;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * When the worker recovers reviews that were left running. The store and the pipeline are
 * mocks, because the cases here need a database that fails at a chosen moment.
 */
class ReviewWorkerTest {

	private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");

	private final ReviewJobStore store = mock(ReviewJobStore.class);
	private final ReviewPipeline pipeline = mock(ReviewPipeline.class);
	private final ReviewWorker worker = new ReviewWorker(store, pipeline, new RetryPolicy(3),
			Clock.fixed(NOW, ZoneOffset.UTC));

	@AfterEach
	void clearInterruptFlag() {
		Thread.interrupted();
	}

	@Test
	void firstRoundRecoversAndLaterRoundsDoNot() {
		when(store.claimNext(NOW)).thenReturn(Optional.empty());

		worker.tick();
		worker.tick();
		worker.tick();

		verify(store, times(1)).recover(NOW);
		verify(store, times(3)).claimNext(NOW);
	}

	@Test
	void roundAfterOneThatBrokeOffRecoversAgainAndThenNoMore() throws InterruptedException {
		DataAccessResourceFailureException databaseDown = new DataAccessResourceFailureException("connection refused");
		when(store.claimNext(NOW)).thenReturn(Optional.of(runningReview())).thenReturn(Optional.empty());
		doThrow(new GitHubException(GitHubErrorCode.REPO_NOT_FOUND, "Repository not found or private")).when(pipeline)
				.run(any());
		// The review has failed, but writing that down fails too: it stays marked as running.
		doThrow(databaseDown).when(store).fail(any(), any(), any());

		assertThatThrownBy(worker::tick).isSameAs(databaseDown);
		verify(store, times(1)).recover(NOW);

		worker.tick();
		verify(store, times(2)).recover(NOW);

		worker.tick();
		worker.tick();
		verify(store, times(2)).recover(NOW);
	}

	@Test
	void recoveryThatFailsIsTriedAgainInTheNextRound() {
		DataAccessResourceFailureException databaseDown = new DataAccessResourceFailureException("connection refused");
		when(store.recover(NOW)).thenThrow(databaseDown).thenReturn(0);
		when(store.claimNext(NOW)).thenReturn(Optional.empty());

		assertThatThrownBy(worker::tick).isSameAs(databaseDown);
		// No review is taken from the queue before the leftovers are dealt with.
		verify(store, never()).claimNext(any());

		worker.tick();
		worker.tick();

		verify(store, times(2)).recover(NOW);
		verify(store, times(2)).claimNext(NOW);
	}

	@Test
	void claimThatFailsMakesTheNextRoundRecoverToo() {
		DataAccessResourceFailureException databaseDown = new DataAccessResourceFailureException("connection refused");
		when(store.claimNext(NOW)).thenThrow(databaseDown).thenReturn(Optional.empty());

		assertThatThrownBy(worker::tick).isSameAs(databaseDown);
		worker.tick();
		worker.tick();

		verify(store, times(2)).recover(NOW);
	}

	@Test
	void reviewThatFailsInTheUsualWayDoesNotCauseAnotherRecovery() throws InterruptedException {
		when(store.claimNext(NOW)).thenReturn(Optional.of(runningReview())).thenReturn(Optional.empty());
		doThrow(new GitHubException(GitHubErrorCode.REPO_NOT_FOUND, "Repository not found or private")).when(pipeline)
				.run(any());

		worker.tick();
		worker.tick();

		verify(store, times(1)).fail(any(), any(), any());
		verify(store, times(1)).recover(NOW);
	}

	@Test
	void interruptedRoundEndsQuietlyAndDoesNotCauseAnotherRecovery() throws InterruptedException {
		when(store.claimNext(NOW)).thenReturn(Optional.of(runningReview())).thenReturn(Optional.empty());
		doThrow(new InterruptedException()).when(pipeline).run(any());

		assertThatCode(worker::tick).doesNotThrowAnyException();
		assertThat(Thread.interrupted()).isTrue();
		worker.tick();

		verify(store, times(1)).recover(NOW);
		verify(store, never()).fail(any(), any(), any());
		verify(store, never()).requeue(any(), any(), any());
	}

	/** A review as the store hands it over: taken from the queue, on its first attempt. */
	private static Review runningReview() {
		Review review = new Review(1, new RepoRef("octo", "demo"));
		review.start(NOW);
		return review;
	}

}
