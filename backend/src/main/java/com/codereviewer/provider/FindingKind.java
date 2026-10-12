package com.codereviewer.provider;

/** What a finding says about the code. */
public enum FindingKind {

	/** Something to fix. Has a severity and points at lines of a file. */
	ISSUE,

	/** Something done well. Has no severity and never affects a score. */
	GOOD_PRACTICE

}
