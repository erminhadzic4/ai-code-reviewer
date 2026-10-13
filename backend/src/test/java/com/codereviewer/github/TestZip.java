package com.codereviewer.github;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds zip archives in memory, shaped like the ones GitHub serves: one root directory
 * with the tree below it. Hostile archives are built the same way, so no binary fixture
 * has to be stored in the repository.
 */
public final class TestZip {

	public static final String ROOT = "octo-demo-0123abc";

	private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
	private final ZipOutputStream zip = new ZipOutputStream(bytes);

	private TestZip() {
	}

	/** An archive that already contains the root directory entry. */
	public static TestZip repository() {
		return new TestZip().entry(ROOT + "/", new byte[0]);
	}

	/** An archive with nothing in it, for entries that must not sit below the root. */
	public static TestZip empty() {
		return new TestZip();
	}

	public TestZip file(String path, String content) {
		return file(path, content.getBytes(StandardCharsets.UTF_8));
	}

	public TestZip file(String path, byte[] content) {
		return entry(ROOT + "/" + path, content);
	}

	public TestZip directory(String path) {
		return entry(ROOT + "/" + path + "/", new byte[0]);
	}

	/** Adds an entry under exactly this name, without the root prefix. */
	public TestZip entry(String name, byte[] content) {
		try {
			zip.putNextEntry(new ZipEntry(name));
			zip.write(content);
			zip.closeEntry();
			return this;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** GitHub writes the commit SHA as the archive comment. */
	public TestZip comment(String comment) {
		zip.setComment(comment);
		return this;
	}

	public byte[] bytes() {
		try {
			zip.close();
			return bytes.toByteArray();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
