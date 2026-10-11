package com.codereviewer.github;

/** Why a file from the repository is left out of the review. */
public enum SkipReason {

	/** Lives in a dependency, build output or tooling directory. */
	EXCLUDED_DIRECTORY,

	/** Documentation, data or a licence file. */
	NON_CODE,

	LOCK_FILE,

	BINARY,

	MINIFIED_OR_GENERATED,

	TOO_LARGE,

	EMPTY,

	/** The path cannot be stored or shown safely. */
	UNSAFE_PATH

}
