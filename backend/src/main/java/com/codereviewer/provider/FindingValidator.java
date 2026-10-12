package com.codereviewer.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.codereviewer.github.SourceFile;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

/**
 * Decides which reported findings can be trusted. A provider's answer is untrusted input: a
 * model can report a file that was never sent or a line that does not exist, and the code it
 * read may have told it to. A finding is kept only if it is well formed and points at lines
 * that exist in a file of the chunk.
 *
 * <p>This is the only place where a finding is dropped, so the number it returns is the
 * number of findings lost. A finding that fails is never repaired, only dropped.
 */
@Component
public class FindingValidator {

	/** A finding that spans more lines than this is about a file, not about a place in it. */
	static final int MAX_FINDING_LINES = 100;

	/** Lines of context shown before and after the lines a finding covers. */
	private static final int CONTEXT_LINES = 1;

	private final Validator validator;

	public FindingValidator(Validator validator) {
		this.validator = validator;
	}

	/**
	 * @param chunk the files that were sent for review
	 * @param reported the findings the provider returned for that chunk
	 */
	public ValidationResult validate(ReviewChunk chunk, List<RawFinding> reported) {
		Map<String, SourceFile> files = chunk.files().stream()
				.collect(Collectors.toMap(SourceFile::path, Function.identity(), (first, second) -> first));
		List<VerifiedFinding> accepted = new ArrayList<>();
		for (RawFinding finding : reported) {
			verify(finding, files).ifPresent(accepted::add);
		}
		return new ValidationResult(accepted, reported.size() - accepted.size());
	}

	private Optional<VerifiedFinding> verify(RawFinding finding, Map<String, SourceFile> files) {
		if (finding == null || !validator.validate(finding).isEmpty() || !hasTheShapeOfItsKind(finding)) {
			return Optional.empty();
		}
		boolean hasLines = finding.lineStart() != null && finding.lineEnd() != null;
		if (!hasLines && (finding.lineStart() != null || finding.lineEnd() != null)) {
			return Optional.empty();
		}
		if (finding.filePath() == null) {
			// About the repository as a whole; lines without a file mean nothing.
			return hasLines ? Optional.empty() : Optional.of(new VerifiedFinding(finding, null, null));
		}
		// Exactly as sent: "./a.py" is not "a.py". Guessing what a path meant would let a
		// finding land on a file it was not written for.
		SourceFile file = files.get(finding.filePath());
		if (file == null) {
			return Optional.empty();
		}
		if (!hasLines) {
			return Optional.of(new VerifiedFinding(finding, null, null));
		}
		List<String> lines = file.lines();
		int start = finding.lineStart();
		int end = finding.lineEnd();
		if (start < 1 || end < start || end > lines.size() || end - start + 1 > MAX_FINDING_LINES) {
			return Optional.empty();
		}
		int snippetStart = Math.max(1, start - CONTEXT_LINES);
		int snippetEnd = Math.min(lines.size(), end + CONTEXT_LINES);
		String snippet = String.join("\n", lines.subList(snippetStart - 1, snippetEnd));
		return Optional.of(new VerifiedFinding(finding, snippet, snippetStart));
	}

	// The same rule as the check constraint on the findings table.
	private static boolean hasTheShapeOfItsKind(RawFinding finding) {
		if (finding.kind() == FindingKind.GOOD_PRACTICE) {
			return finding.severity() == null;
		}
		return finding.severity() != null && finding.filePath() != null && finding.lineStart() != null
				&& finding.lineEnd() != null;
	}

}
