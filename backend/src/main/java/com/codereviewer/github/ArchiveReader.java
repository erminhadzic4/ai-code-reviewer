package com.codereviewer.github;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Reads the zip archive of a repository as a stream and keeps the files worth reviewing.
 *
 * <p>The archive is untrusted input. Nothing is written to disk, sizes declared inside the
 * archive are ignored and only bytes actually read are counted, and every limit stops the
 * reading as soon as it is crossed.
 */
final class ArchiveReader {

	/** Longest path the findings table can store. */
	static final int MAX_PATH_LENGTH = 1024;

	private static final String TOO_LARGE = "Repository is too large to review";

	private static final String INVALID = "GitHub returned an archive that could not be read";

	private static final Pattern DRIVE_PREFIX = Pattern.compile("^[A-Za-z]:.*", Pattern.DOTALL);

	private static final Pattern SEPARATORS = Pattern.compile("[/\\\\]");

	private final long maxDownloadBytes;
	private final long maxUncompressedBytes;
	private final int maxEntries;
	private final int maxFileBytes;
	private final int maxFiles;
	private final long maxSourceBytes;

	ArchiveReader(GitHubProperties properties) {
		this.maxDownloadBytes = properties.maxDownloadSize().toBytes();
		this.maxUncompressedBytes = properties.maxUncompressedSize().toBytes();
		this.maxEntries = properties.maxEntries();
		this.maxFileBytes = Math.toIntExact(properties.maxFileSize().toBytes());
		this.maxFiles = properties.maxFiles();
		this.maxSourceBytes = properties.maxSourceSize().toBytes();
	}

	/**
	 * @param archive the zip archive as GitHub serves it: one root directory holding the tree
	 * @throws GitHubException {@code REPO_TOO_LARGE} when a limit is crossed,
	 * {@code ARCHIVE_INVALID} when the archive is damaged or has a path no repository can have
	 * @throws IOException when the stream itself fails, for example a dropped connection
	 */
	RepoSnapshot read(RepoHead head, InputStream archive) throws IOException {
		try {
			return new Reading(new ArchiveSource(archive, maxDownloadBytes)).run(head);
		}
		catch (ZipException | IllegalArgumentException ex) {
			// IllegalArgumentException: an entry name that is not valid UTF-8.
			throw new GitHubException(GitHubErrorCode.ARCHIVE_INVALID, INVALID, ex);
		}
	}

	/** The state of reading one archive. */
	private final class Reading {

		private final ArchiveSource source;
		private final ZipInputStream zip;
		private final byte[] buffer = new byte[8192];
		private final List<SourceFile> files = new ArrayList<>();
		private final Set<String> keptPaths = new HashSet<>();
		private final Map<SkipReason, Integer> skipped = new EnumMap<>(SkipReason.class);
		private long uncompressedBytes;
		private long sourceBytes;
		private String root;

		Reading(ArchiveSource source) {
			this.source = source;
			this.zip = new ZipInputStream(source);
		}

		RepoSnapshot run(RepoHead head) throws IOException {
			int entries = 0;
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (++entries > maxEntries) {
					throw new GitHubException(GitHubErrorCode.REPO_TOO_LARGE, TOO_LARGE);
				}
				readEntry(entry.getName());
				// Whatever is left is inflated here and counted, never skipped blindly.
				drain();
			}
			// getNextEntry() returns null both at the real end and when the stream simply
			// stops, and for content that is not a zip at all. The rest of the stream tells
			// which one it was.
			source.readToEnd(buffer);
			if (!source.startsWithEntry()) {
				throw new GitHubException(GitHubErrorCode.ARCHIVE_INVALID, INVALID);
			}
			if (!source.endsWithEndRecord()) {
				throw new EOFException("The archive ended before its end record");
			}
			if (entries == 0) {
				throw new GitHubException(GitHubErrorCode.ARCHIVE_INVALID, INVALID);
			}
			return new RepoSnapshot(head, files, skipped);
		}

		private void readEntry(String name) throws IOException {
			String path = pathInsideRoot(name);
			if (path.isEmpty() || path.endsWith("/")) {
				return; // a directory
			}
			if (!isSafe(path) || keptPaths.contains(path)) {
				skip(SkipReason.UNSAFE_PATH);
				return;
			}
			Optional<SkipReason> byPath = FileFilter.checkPath(path);
			if (byPath.isPresent()) {
				skip(byPath.get());
				return;
			}
			byte[] bytes = zip.readNBytes(maxFileBytes + 1);
			count(bytes.length);
			if (bytes.length > maxFileBytes) {
				skip(SkipReason.TOO_LARGE);
				return;
			}
			Optional<String> text = decode(bytes);
			if (text.isEmpty()) {
				skip(SkipReason.BINARY);
				return;
			}
			Optional<SkipReason> byContent = FileFilter.checkContent(text.get());
			if (byContent.isPresent()) {
				skip(byContent.get());
				return;
			}
			keep(path, text.get(), bytes.length);
		}

		// GitHub puts the whole tree under one directory named after the repository and the
		// commit. Git cannot store "..", absolute paths or anything outside that directory, so
		// such an entry means this is not a repository archive and nothing in it is trusted.
		private String pathInsideRoot(String name) {
			if (name.startsWith("/") || name.startsWith("\\") || DRIVE_PREFIX.matcher(name).matches()) {
				throw new GitHubException(GitHubErrorCode.ARCHIVE_INVALID, INVALID);
			}
			for (String segment : SEPARATORS.split(name)) {
				if (segment.equals("..")) {
					throw new GitHubException(GitHubErrorCode.ARCHIVE_INVALID, INVALID);
				}
			}
			int slash = name.indexOf('/');
			if (slash <= 0) {
				throw new GitHubException(GitHubErrorCode.ARCHIVE_INVALID, INVALID);
			}
			String prefix = name.substring(0, slash + 1);
			if (root == null) {
				root = prefix;
			}
			else if (!root.equals(prefix)) {
				throw new GitHubException(GitHubErrorCode.ARCHIVE_INVALID, INVALID);
			}
			return name.substring(slash + 1);
		}

		// Legal in git, but not something to store, show or send to a model.
		private boolean isSafe(String path) {
			if (path.length() > MAX_PATH_LENGTH || path.indexOf('\\') >= 0) {
				return false;
			}
			if (path.chars().anyMatch(Character::isISOControl)) {
				return false;
			}
			for (String segment : path.split("/", -1)) {
				if (segment.isEmpty() || segment.equals(".")) {
					return false;
				}
			}
			return true;
		}

		private void keep(String path, String text, int size) {
			sourceBytes += size;
			if (files.size() >= maxFiles || sourceBytes > maxSourceBytes) {
				throw new GitHubException(GitHubErrorCode.REPO_TOO_LARGE, TOO_LARGE);
			}
			files.add(new SourceFile(path, text));
			keptPaths.add(path);
		}

		private void skip(SkipReason reason) {
			skipped.merge(reason, 1, Integer::sum);
		}

		private void drain() throws IOException {
			int read;
			while ((read = zip.read(buffer)) != -1) {
				count(read);
			}
		}

		private void count(int bytes) {
			uncompressedBytes += bytes;
			if (uncompressedBytes > maxUncompressedBytes) {
				throw new GitHubException(GitHubErrorCode.REPO_TOO_LARGE, TOO_LARGE);
			}
		}

	}

	/** Strict UTF-8: a file that is not valid text is treated as binary, not repaired. */
	private static Optional<String> decode(byte[] bytes) {
		try {
			String text = StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes))
					.toString();
			return Optional.of(text.startsWith("﻿") ? text.substring(1) : text);
		}
		catch (CharacterCodingException ex) {
			return Optional.empty();
		}
	}

	/**
	 * The downloaded bytes. Fails once more have been read than a repository archive may
	 * have, and remembers the first and the last bytes so that an archive cut short can be
	 * told apart from a complete one.
	 */
	private static final class ArchiveSource extends FilterInputStream {

		private static final byte[] ENTRY_SIGNATURE = { 'P', 'K', 3, 4 };

		private static final byte[] END_SIGNATURE = { 'P', 'K', 5, 6 };

		/** Fixed part of the end record; a comment of up to 65535 bytes may follow. */
		private static final int END_RECORD_LENGTH = 22;

		private final long limit;
		private final byte[] head = new byte[ENTRY_SIGNATURE.length];
		private final byte[] tail = new byte[END_RECORD_LENGTH + 0xFFFF];
		private final byte[] single = new byte[1];
		private long total;

		ArchiveSource(InputStream in, long limit) {
			super(in);
			this.limit = limit;
		}

		@Override
		public int read() throws IOException {
			return read(single, 0, 1) == -1 ? -1 : single[0] & 0xFF;
		}

		@Override
		public int read(byte[] target, int offset, int length) throws IOException {
			int count = super.read(target, offset, length);
			if (count > 0) {
				remember(target, offset, count);
				if (total > limit) {
					throw new GitHubException(GitHubErrorCode.REPO_TOO_LARGE, TOO_LARGE);
				}
			}
			return count;
		}

		// Skipping in the underlying stream would bypass the counting.
		@Override
		public long skip(long count) throws IOException {
			if (count <= 0) {
				return 0;
			}
			byte[] discarded = new byte[(int) Math.min(count, 8192)];
			return Math.max(read(discarded, 0, discarded.length), 0);
		}

		void readToEnd(byte[] buffer) throws IOException {
			while (read(buffer, 0, buffer.length) != -1) {
				// only counting and remembering
			}
		}

		/** An archive with at least one entry starts with an entry signature. */
		boolean startsWithEntry() {
			int available = (int) Math.min(total, head.length);
			return available > 0 && Arrays.equals(head, 0, available, ENTRY_SIGNATURE, 0, available);
		}

		/**
		 * A complete archive ends with an end record whose comment runs exactly to the last
		 * byte. ZipInputStream cannot tell: it stops quietly when the next entry is missing.
		 */
		boolean endsWithEndRecord() {
			int length = (int) Math.min(total, tail.length);
			int start = (int) ((total - length) % tail.length);
			for (int i = 0; i + END_RECORD_LENGTH <= length; i++) {
				if (tailMatches(start, i, END_SIGNATURE)) {
					int comment = tailAt(start, i + 20) | (tailAt(start, i + 21) << 8);
					if (i + END_RECORD_LENGTH + comment == length) {
						return true;
					}
				}
			}
			return false;
		}

		private void remember(byte[] bytes, int offset, int count) {
			for (int i = 0; i < count && total + i < head.length; i++) {
				head[(int) total + i] = bytes[offset + i];
			}
			// Only the last tail.length bytes can matter.
			int copied = Math.max(0, count - tail.length);
			long position = total + copied;
			while (copied < count) {
				int index = (int) (position % tail.length);
				int chunk = Math.min(count - copied, tail.length - index);
				System.arraycopy(bytes, offset + copied, tail, index, chunk);
				copied += chunk;
				position += chunk;
			}
			total += count;
		}

		private boolean tailMatches(int start, int index, byte[] expected) {
			for (int i = 0; i < expected.length; i++) {
				if (tailAt(start, index + i) != (expected[i] & 0xFF)) {
					return false;
				}
			}
			return true;
		}

		private int tailAt(int start, int index) {
			return tail[(start + index) % tail.length] & 0xFF;
		}

	}

}
