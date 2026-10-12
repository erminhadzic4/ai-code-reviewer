package com.codereviewer.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SourceFileTest {

	@ParameterizedTest(name = "{0}")
	@MethodSource("contents")
	void linesAreSplitTheSameWayForEveryKindOfLineBreak(String description, String content, List<String> expected) {
		assertThat(new SourceFile("a.txt", content).lines()).isEqualTo(expected);
	}

	static Stream<Arguments> contents() {
		return Stream.of(
				Arguments.of("no content", "", List.of()),
				Arguments.of("one line without a break", "a", List.of("a")),
				Arguments.of("a break at the end adds no line", "a\nb\n", List.of("a", "b")),
				Arguments.of("no break at the end", "a\nb", List.of("a", "b")),
				Arguments.of("Windows line breaks", "a\r\nb\r\n", List.of("a", "b")),
				Arguments.of("old Mac line breaks", "a\rb", List.of("a", "b")),
				Arguments.of("mixed line breaks", "a\r\nb\nc", List.of("a", "b", "c")),
				Arguments.of("an empty line in the middle is a line", "a\n\nb", List.of("a", "", "b")),
				Arguments.of("empty lines at the end are lines", "a\n\n\n", List.of("a", "", "")),
				Arguments.of("only a line break", "\n", List.of("")),
				Arguments.of("indentation is kept", "\tif (x) {\n\t\ty();\n", List.of("\tif (x) {", "\t\ty();")));
	}

}
