package com.codereviewer.github;

import java.util.List;

/**
 * One file kept for review.
 *
 * @param path path relative to the repository root, with {@code /} between segments
 * @param content the file as text
 */
public record SourceFile(String path, String content) {

	/**
	 * The lines of the file, without their line breaks. Line {@code n} of the file is element
	 * {@code n - 1}.
	 *
	 * <p>This is the only definition of a line in the application: the numbers shown to a
	 * model, the check that a reported line exists and the snippet cut out for a finding all
	 * come from here, so they cannot disagree. A line ends at {@code \n}, {@code \r\n} or
	 * {@code \r}, and a break at the very end of the file does not start another line.
	 */
	public List<String> lines() {
		return content.lines().toList();
	}

}
