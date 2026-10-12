package com.codereviewer.provider;

import java.util.List;

import com.codereviewer.github.SourceFile;

/**
 * The part of a repository that is reviewed in one call to a provider.
 *
 * @param files whole files, in the order they are sent
 */
public record ReviewChunk(List<SourceFile> files) {

	public ReviewChunk {
		files = List.copyOf(files);
	}

}
