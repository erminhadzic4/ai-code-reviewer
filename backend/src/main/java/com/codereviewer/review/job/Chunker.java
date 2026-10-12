package com.codereviewer.review.job;

import java.util.ArrayList;
import java.util.List;

import com.codereviewer.config.ReviewProperties;
import com.codereviewer.github.SourceFile;
import com.codereviewer.provider.NumberedSource;
import com.codereviewer.provider.ReviewChunk;
import org.springframework.stereotype.Component;

/**
 * Groups the files of a repository into chunks, each small enough for one request to a
 * provider. The same files always give the same chunks.
 *
 * <p>A file costs as many characters as it takes up when it is sent, line numbers included.
 * Files keep their order and are never split: a finding needs its whole file as context. A
 * file that alone costs more than the budget becomes a chunk of its own instead of being
 * left out, because a review must not silently cover only part of a repository.
 */
@Component
public class Chunker {

	private final int budget;

	public Chunker(ReviewProperties properties) {
		this.budget = properties.chunkBudget();
	}

	public List<ReviewChunk> chunk(List<SourceFile> files) {
		List<ReviewChunk> chunks = new ArrayList<>();
		List<SourceFile> current = new ArrayList<>();
		long used = 0;
		for (SourceFile file : files) {
			int cost = NumberedSource.length(file);
			if (!current.isEmpty() && used + cost > budget) {
				chunks.add(new ReviewChunk(current));
				current = new ArrayList<>();
				used = 0;
			}
			current.add(file);
			used += cost;
		}
		if (!current.isEmpty()) {
			chunks.add(new ReviewChunk(current));
		}
		return List.copyOf(chunks);
	}

}
