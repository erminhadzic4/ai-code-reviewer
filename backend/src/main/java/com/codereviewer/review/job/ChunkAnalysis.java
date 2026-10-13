package com.codereviewer.review.job;

import java.util.List;

import com.codereviewer.provider.VerifiedFinding;

/**
 * What the provider found in the chunks of one repository, before anything is scored or
 * stored.
 *
 * @param findings findings that passed validation, in the order the chunks were sent
 * @param findingsDiscarded findings that failed validation, summed over the chunks
 * @param filesAnalyzed files of the chunks the provider reviewed
 * @param filesFailed files the provider could not review, even in a smaller chunk
 * @param linesAnalyzed lines of the files that were reviewed; failed files do not count
 */
public record ChunkAnalysis(List<VerifiedFinding> findings, int findingsDiscarded, int filesAnalyzed, int filesFailed,
		int linesAnalyzed) {

	public ChunkAnalysis {
		findings = List.copyOf(findings);
	}

}
