package com.codereviewer.review;

/** Where a review is as a job. */
public enum ReviewStatus {

	/** Waiting for the worker. */
	QUEUED,

	RUNNING,

	/** Finished; the review points to its analysis. */
	COMPLETED,

	/** Gave up; the review carries an error code. */
	FAILED

}
