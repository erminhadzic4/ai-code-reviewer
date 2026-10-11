package com.codereviewer.github;

import java.util.List;
import java.util.Map;

/**
 * The reviewable content of a repository at one commit.
 *
 * @param head the commit the content belongs to
 * @param files files kept for review, in archive order
 * @param skipped number of files left out, by reason
 */
public record RepoSnapshot(RepoHead head, List<SourceFile> files, Map<SkipReason, Integer> skipped) {

	public RepoSnapshot {
		files = List.copyOf(files);
		skipped = Map.copyOf(skipped);
	}

}
