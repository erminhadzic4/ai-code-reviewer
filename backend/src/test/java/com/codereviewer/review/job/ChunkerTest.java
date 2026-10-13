package com.codereviewer.review.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;

import com.codereviewer.config.ReviewProperties;
import com.codereviewer.github.SourceFile;
import com.codereviewer.provider.NumberedSource;
import com.codereviewer.provider.ProviderType;
import com.codereviewer.provider.ReviewChunk;
import org.junit.jupiter.api.Test;

class ChunkerTest {

	/** Every file made by {@link #file(String)} costs this much. */
	private static final int COST = NumberedSource.length(file("a.py"));

	@Test
	void filesThatFitTheBudgetTogetherMakeOneChunk() {
		List<SourceFile> files = files(3);

		List<ReviewChunk> chunks = chunker(3 * COST).chunk(files);

		assertThat(chunks).singleElement().satisfies(chunk -> assertThat(chunk.files()).isEqualTo(files));
	}

	@Test
	void fileThatWouldCrossTheBudgetStartsTheNextChunk() {
		List<SourceFile> files = files(3);

		List<ReviewChunk> chunks = chunker(3 * COST - 1).chunk(files);

		assertThat(chunks).extracting(chunk -> chunk.files().size()).containsExactly(2, 1);
	}

	@Test
	void chunksAreFilledInOrderUpToTheBudget() {
		List<SourceFile> files = files(7);

		List<ReviewChunk> chunks = chunker(3 * COST).chunk(files);

		assertThat(chunks).extracting(chunk -> chunk.files().size()).containsExactly(3, 3, 1);
		assertThat(chunks.stream().flatMap(chunk -> chunk.files().stream())).containsExactlyElementsOf(files);
	}

	@Test
	void fileLargerThanTheBudgetIsAChunkOfItsOwnAndIsNotSplit() {
		SourceFile small = file("small.py");
		SourceFile huge = new SourceFile("huge.py", "x = 1\n".repeat(1000));
		SourceFile other = file("other.py");

		List<ReviewChunk> chunks = chunker(2 * COST).chunk(List.of(small, huge, other));

		assertThat(chunks).extracting(ReviewChunk::files)
				.containsExactly(List.of(small), List.of(huge), List.of(other));
	}

	@Test
	void fileLargerThanTheBudgetAtTheStartDoesNotLeaveAnEmptyChunk() {
		SourceFile huge = new SourceFile("huge.py", "x = 1\n".repeat(1000));

		List<ReviewChunk> chunks = chunker(COST).chunk(List.of(huge, file("a.py")));

		assertThat(chunks).hasSize(2).allSatisfy(chunk -> assertThat(chunk.files()).isNotEmpty());
	}

	@Test
	void costOfAFileIncludesItsLineNumbers() {
		SourceFile file = new SourceFile("f.py", "aaaaaaaaaaaaaaaaaaaa\n".repeat(5));
		int markers = NumberedSource.length(new SourceFile("f.py", ""));
		// Enough for both files if only their content and markers counted.
		int withoutNumbers = 2 * (file.content().length() + markers);

		List<ReviewChunk> chunks = chunker(withoutNumbers).chunk(List.of(file, file));

		assertThat(NumberedSource.length(file)).isLessThanOrEqualTo(withoutNumbers);
		assertThat(chunks).hasSize(2);
	}

	@Test
	void noFilesGiveNoChunks() {
		assertThat(chunker(1000).chunk(List.of())).isEmpty();
	}

	@Test
	void sameFilesAlwaysGiveTheSameChunks() {
		List<SourceFile> files = files(10);

		assertThat(chunker(4 * COST).chunk(files)).isEqualTo(chunker(4 * COST).chunk(files));
	}

	@Test
	void noFileIsLostOrRepeated() {
		List<SourceFile> files = IntStream.range(0, 40)
				.mapToObj(i -> new SourceFile("f" + i + ".py", "line\n".repeat(1 + (i * 37) % 60)))
				.toList();

		List<ReviewChunk> chunks = chunker(500).chunk(files);

		assertThat(chunks.stream().flatMap(chunk -> chunk.files().stream())).containsExactlyElementsOf(files);
	}

	private static Chunker chunker(int budget) {
		return new Chunker(new ReviewProperties(ProviderType.FAKE, budget, 3));
	}

	private static List<SourceFile> files(int count) {
		return IntStream.range(0, count).mapToObj(i -> file((char) ('a' + i) + ".py")).toList();
	}

	private static SourceFile file(String path) {
		return new SourceFile(path, "one\ntwo\nthree\n");
	}

}
