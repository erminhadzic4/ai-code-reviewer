package com.codereviewer.provider.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.codereviewer.github.SourceFile;
import com.codereviewer.provider.FindingValidator;
import com.codereviewer.provider.NumberedSource;
import com.codereviewer.provider.RawFinding;
import com.codereviewer.provider.ReviewChunk;
import com.codereviewer.provider.ValidationResult;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The only tests that talk to the real Gemini API. They are skipped unless
 * GEMINI_LIVE_TEST=true is set, which CI never does: every other test uses a local stand-in.
 * Run them by hand, with GEMINI_API_KEY set, to check that the stand-in still matches the
 * real API. Each test costs one request of the free quota.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_LIVE_TEST", matches = "true")
@ExtendWith(OutputCaptureExtension.class)
class GeminiReviewProviderLiveTest {

	/** The shipped chunk budget, in characters. */
	private static final int CHUNK_BUDGET = 200_000;

	@Test
	void reviewsASmallChunkAndReportsFindingsThatPassValidation() {
		ReviewChunk chunk = new ReviewChunk(List.of(
				new SourceFile("src/UserDao.java", """
						package example;

						import java.sql.Connection;
						import java.sql.DriverManager;
						import java.sql.ResultSet;
						import java.sql.Statement;

						public class UserDao {

						    private static final String PASSWORD = "admin123";

						    public ResultSet find(String name) throws Exception {
						        Connection connection = DriverManager.getConnection("jdbc:postgresql://db/app", "app", PASSWORD);
						        Statement statement = connection.createStatement();
						        return statement.executeQuery("SELECT * FROM users WHERE name = '" + name + "'");
						    }
						}
						"""),
				new SourceFile("src/Money.java", """
						package example;

						public record Money(long cents) {

						    public Money plus(Money other) {
						        return new Money(Math.addExact(cents, other.cents));
						    }
						}
						""")));

		List<RawFinding> findings = provider().review(chunk);

		try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
			ValidationResult result = new FindingValidator(factory.getValidator()).validate(chunk, findings);
			assertThat(findings).isNotEmpty();
			assertThat(result.accepted()).isNotEmpty();
			assertThat(result.discarded()).as("findings the validator dropped").isZero();
		}
	}

	/**
	 * Sends this project's own sources, as many as fit the budget, so that the relation of
	 * characters to tokens can be read from the log line the provider writes.
	 */
	@Test
	void reviewsAChunkCloseToTheFullBudget(CapturedOutput output) {
		ReviewChunk chunk = ownSourcesUpTo(CHUNK_BUDGET);
		int characters = NumberedSource.of(chunk).length();

		List<RawFinding> findings = provider().review(chunk);

		assertThat(characters).isBetween(CHUNK_BUDGET * 9 / 10, CHUNK_BUDGET);
		assertThat(findings).hasSizeLessThanOrEqualTo(GeminiPrompt.MAX_FINDINGS);
		assertThat(output.getAll()).contains("Gemini reviewed " + characters + " characters");
		try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
			ValidationResult result = new FindingValidator(factory.getValidator()).validate(chunk, findings);
			System.out.printf("Full-budget chunk: %d files, %d characters, %d findings, %d discarded%n",
					chunk.files().size(), characters, findings.size(), result.discarded());
		}
	}

	private static GeminiReviewProvider provider() {
		TestGeminiProperties settings = new TestGeminiProperties();
		settings.apiKey = System.getenv("GEMINI_API_KEY");
		return new GeminiReviewProvider(settings.build());
	}

	private static ReviewChunk ownSourcesUpTo(int budget) {
		List<SourceFile> files = new ArrayList<>();
		int used = 0;
		try (Stream<Path> paths = Files.walk(Path.of("src"))) {
			for (Path path : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
				SourceFile file = new SourceFile(path.toString().replace('\\', '/'), Files.readString(path));
				int cost = NumberedSource.length(file) + 1;
				if (used + cost <= budget) {
					files.add(file);
					used += cost;
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return new ReviewChunk(files);
	}

}
