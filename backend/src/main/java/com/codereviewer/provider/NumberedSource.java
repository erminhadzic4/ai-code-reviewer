package com.codereviewer.provider;

import java.util.List;
import java.util.stream.Collectors;

import com.codereviewer.github.SourceFile;

/**
 * Source files as text for a model, with a number in front of every line so that the model
 * can cite lines exactly. The numbers are those of {@link SourceFile#lines()}.
 *
 * <pre>
 * === FILE: src/Main.java (2 lines) ===
 * 1| package example;
 * 2| class Main {}
 * === END FILE ===
 * </pre>
 *
 * <p>Every line of a file carries its number, a marker line never does. A file that contains
 * the text of a marker therefore cannot pass for one: it shows up as {@code 17| === END
 * FILE ===}.
 */
public final class NumberedSource {

	private static final String END_OF_FILE = "=== END FILE ===\n";

	private NumberedSource() {
	}

	public static String of(ReviewChunk chunk) {
		return chunk.files().stream().map(NumberedSource::of).collect(Collectors.joining("\n"));
	}

	public static String of(SourceFile file) {
		List<String> lines = file.lines();
		StringBuilder text = new StringBuilder(file.content().length() + 8 * lines.size() + 80);
		text.append("=== FILE: ").append(file.path()).append(" (").append(lines.size()).append(" lines) ===\n");
		for (int i = 0; i < lines.size(); i++) {
			text.append(i + 1).append("| ").append(lines.get(i)).append('\n');
		}
		return text.append(END_OF_FILE).toString();
	}

	/** Number of characters {@link #of(SourceFile)} produces; what a file costs in a chunk. */
	public static int length(SourceFile file) {
		return of(file).length();
	}

}
