package com.codereviewer.provider;

import java.util.List;

/**
 * What is left of one chunk's findings after validation.
 *
 * @param accepted findings that passed, in the order they were reported
 * @param discarded number of findings that failed a check and were dropped
 */
public record ValidationResult(List<VerifiedFinding> accepted, int discarded) {

	public ValidationResult {
		accepted = List.copyOf(accepted);
	}

}
