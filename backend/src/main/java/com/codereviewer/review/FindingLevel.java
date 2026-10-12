package com.codereviewer.review;

import com.codereviewer.provider.FindingKind;
import com.codereviewer.provider.Severity;

/**
 * The counter of the report a finding falls into. It is derived from the kind and the
 * severity every time it is needed and never stored, so it cannot drift from them. The
 * category plays no part: it is shown as its own label.
 */
public enum FindingLevel {

	CRITICAL,

	WARNING,

	SMELL,

	GOOD_PRACTICE;

	/**
	 * @param severity required for an issue, {@code null} for a good practice
	 * @throws IllegalArgumentException for a combination the database does not allow either
	 */
	public static FindingLevel of(FindingKind kind, Severity severity) {
		if (kind == FindingKind.GOOD_PRACTICE) {
			if (severity != null) {
				throw new IllegalArgumentException("A good practice has no severity");
			}
			return GOOD_PRACTICE;
		}
		if (severity == null) {
			throw new IllegalArgumentException("An issue needs a severity");
		}
		return switch (severity) {
			case CRITICAL -> CRITICAL;
			case HIGH, MEDIUM -> WARNING;
			case LOW -> SMELL;
		};
	}

}
