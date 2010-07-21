import static org.eclipse.jgit.lib.Constants.encode;
import static org.eclipse.jgit.lib.FileMode.GITLINK;
import java.io.ByteArrayOutputStream;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.HunkHeader;
import org.eclipse.jgit.patch.FileHeader.PatchType;
import org.eclipse.jgit.util.QuotedString;
import org.eclipse.jgit.util.io.DisabledOutputStream;
	private final OutputStream out;

	private Repository db;

	private int abbreviationLength;

	private RawText.Factory rawTextFactory = RawText.FACTORY;

	/**
	 * Create a new formatter with a default level of context.
	 *
	 * @param out
	 *            the stream the formatter will write line data to. This stream
	 *            should have buffering arranged by the caller, as many small
	 *            writes are performed to it.
	 */
	public DiffFormatter(OutputStream out) {
		this.out = out;
		setAbbreviationLength(8);
	}

	/** @return the stream we are outputting data to. */
	protected OutputStream getOutputStream() {
		return out;
	}

	/**
	 * Set the repository the formatter can load object contents from.
	 *
	 * @param repository
	 *            source repository holding referenced objects.
	 */
	public void setRepository(Repository repository) {
		db = repository;
			throw new IllegalArgumentException(
					JGitText.get().contextMustBeNonNegative);
	/**
	 * Change the number of digits to show in an ObjectId.
	 *
	 * @param count
	 *            number of digits to show in an ObjectId.
	 */
	public void setAbbreviationLength(final int count) {
		if (count < 0)
			throw new IllegalArgumentException(
					JGitText.get().abbreviationLengthMustBeNonNegative);
		abbreviationLength = count;
	}

	/**
	 * Set the helper that constructs difference output.
	 *
	 * @param type
	 *            the factory to create different output. Different types of
	 *            factories can produce different whitespace behavior, for
	 *            example.
	 * @see RawText#FACTORY
	 * @see RawTextIgnoreAllWhitespace#FACTORY
	 * @see RawTextIgnoreLeadingWhitespace#FACTORY
	 * @see RawTextIgnoreTrailingWhitespace#FACTORY
	 * @see RawTextIgnoreWhitespaceChange#FACTORY
	 */
	public void setRawTextFactory(RawText.Factory type) {
		rawTextFactory = type;
	}

	/**
	 * Flush the underlying output stream of this formatter.
	 *
	 * @throws IOException
	 *             the stream's own flush method threw an exception.
	 */
	public void flush() throws IOException {
		out.flush();
	}

	/**
	 * Format a patch script from a list of difference entries.
	 *
	 * @param entries
	 *            entries describing the affected files.
	 * @throws IOException
	 *             a file's content cannot be read, or the output stream cannot
	 *             be written to.
	 */
	public void format(List<? extends DiffEntry> entries) throws IOException {
		for (DiffEntry ent : entries)
			format(ent);
	}

	/**
	 * Format a patch script for one file entry.
	 *
	 * @param ent
	 *            the entry to be formatted.
	 * @throws IOException
	 *             a file's content cannot be read, or the output stream cannot
	 *             be written to.
	 */
	public void format(DiffEntry ent) throws IOException {
		writeDiffHeader(out, ent);

		if (ent.getOldMode() == GITLINK || ent.getNewMode() == GITLINK) {
			writeGitLinkDiffText(out, ent);
		} else {
			byte[] aRaw = open(ent.getOldMode(), ent.getOldId());
			byte[] bRaw = open(ent.getNewMode(), ent.getNewId());

			if (RawText.isBinary(aRaw) || RawText.isBinary(bRaw)) {
				out.write(encodeASCII("Binary files differ\n"));

			} else {
				RawText a = rawTextFactory.create(aRaw);
				RawText b = rawTextFactory.create(bRaw);
				formatEdits(a, b, new MyersDiff(a, b).getEdits());
			}
		}
	}

	private void writeGitLinkDiffText(OutputStream o, DiffEntry ent)
			throws IOException {
		if (ent.getOldMode() == GITLINK) {
			o.write(encodeASCII("-Subproject commit " + ent.getOldId().name()
					+ "\n"));
		}
		if (ent.getNewMode() == GITLINK) {
			o.write(encodeASCII("+Subproject commit " + ent.getNewId().name()
					+ "\n"));
		}
	}

	private void writeDiffHeader(OutputStream o, DiffEntry ent)
			throws IOException {
		String oldName = quotePath("a/" + ent.getOldName());
		String newName = quotePath("b/" + ent.getNewName());
		o.write(encode("diff --git " + oldName + " " + newName + "\n"));

		switch (ent.getChangeType()) {
		case ADD:
			o.write(encodeASCII("new file mode "));
			ent.getNewMode().copyTo(o);
			o.write('\n');
			break;

		case DELETE:
			o.write(encodeASCII("deleted file mode "));
			ent.getOldMode().copyTo(o);
			o.write('\n');
			break;

		case RENAME:
			o.write(encodeASCII("similarity index " + ent.getScore() + "%"));
			o.write('\n');

			o.write(encode("rename from " + quotePath(ent.getOldName())));
			o.write('\n');

			o.write(encode("rename to " + quotePath(ent.getNewName())));
			o.write('\n');
			break;

		case COPY:
			o.write(encodeASCII("similarity index " + ent.getScore() + "%"));
			o.write('\n');

			o.write(encode("copy from " + quotePath(ent.getOldName())));
			o.write('\n');

			o.write(encode("copy to " + quotePath(ent.getNewName())));
			o.write('\n');

			if (!ent.getOldMode().equals(ent.getNewMode())) {
				o.write(encodeASCII("new file mode "));
				ent.getNewMode().copyTo(o);
				o.write('\n');
			}
			break;
		}

		switch (ent.getChangeType()) {
		case RENAME:
		case MODIFY:
			if (!ent.getOldMode().equals(ent.getNewMode())) {
				o.write(encodeASCII("old mode "));
				ent.getOldMode().copyTo(o);
				o.write('\n');

				o.write(encodeASCII("new mode "));
				ent.getNewMode().copyTo(o);
				o.write('\n');
			}
		}

		o.write(encodeASCII("index " //
				+ format(ent.getOldId()) //
				+ ".." //
				+ format(ent.getNewId())));
		if (ent.getOldMode().equals(ent.getNewMode())) {
			o.write(' ');
			ent.getNewMode().copyTo(o);
		}
		o.write('\n');
		o.write(encode("--- " + oldName + '\n'));
		o.write(encode("+++ " + newName + '\n'));
	}

	private String format(AbbreviatedObjectId oldId) {
		if (oldId.isComplete() && db != null)
			oldId = oldId.toObjectId().abbreviate(db, abbreviationLength);
		return oldId.name();
	}

	private static String quotePath(String name) {
		String q = QuotedString.GIT_PATH.quote(name);
		return ('"' + name + '"').equals(q) ? name : q;
	}

	private byte[] open(FileMode mode, AbbreviatedObjectId id)
			throws IOException {
		if (mode == FileMode.MISSING)
			return new byte[] {};

		if (mode.getObjectType() != Constants.OBJ_BLOB)
			return new byte[] {};

		if (db == null)
			throw new IllegalStateException(JGitText.get().repositoryIsRequired);
		if (id.isComplete()) {
			ObjectLoader ldr = db.openObject(id.toObjectId());
			return ldr.getCachedBytes();
		}

		return new byte[] {};
	}

	public void format(final FileHeader head, final RawText a, final RawText b)
			throws IOException {
		formatEdits(a, b, head.toEditList());
	 *
	 * @param a
	 *            the text A which was compared
	 * @param b
	 *            the text B which was compared
	 * @param edits
	 *            some differences which have been calculated between A and B
	public void formatEdits(final RawText a, final RawText b,
			final EditList edits) throws IOException {
			writeHunkHeader(aCur, aEnd, bCur, bEnd);
					writeContextLine(a, aCur);
					if (isEndOfLineMissing(a, aCur))
						out.write(noNewLine);
					writeRemovedLine(a, aCur);
					if (isEndOfLineMissing(a, aCur))
						out.write(noNewLine);
					aCur++;
					writeAddedLine(b, bCur);
					if (isEndOfLineMissing(b, bCur))
						out.write(noNewLine);
					bCur++;
	/**
	 * Output a line of context (unmodified line).
	 *
	 * @param text
	 *            RawText for accessing raw data
	 * @param line
	 *            the line number within text
	 * @throws IOException
	 */
	protected void writeContextLine(final RawText text, final int line)
			throws IOException {
		writeLine(' ', text, line);
	}

	private boolean isEndOfLineMissing(final RawText text, final int line) {
		return line + 1 == text.size() && text.isMissingNewlineAtEnd();
	}

	/**
	 * Output an added line.
	 *
	 * @param text
	 *            RawText for accessing raw data
	 * @param line
	 *            the line number within text
	 * @throws IOException
	 */
	protected void writeAddedLine(final RawText text, final int line)
			throws IOException {
		writeLine('+', text, line);
	}

	/**
	 * Output a removed line
	 *
	 * @param text
	 *            RawText for accessing raw data
	 * @param line
	 *            the line number within text
	 * @throws IOException
	 */
	protected void writeRemovedLine(final RawText text, final int line)
			throws IOException {
		writeLine('-', text, line);
	}

	/**
	 * Output a hunk header
	 *
	 * @param aStartLine
	 *            within first source
	 * @param aEndLine
	 *            within first source
	 * @param bStartLine
	 *            within second source
	 * @param bEndLine
	 *            within second source
	 * @throws IOException
	 */
	protected void writeHunkHeader(int aStartLine, int aEndLine,
			int bStartLine, int bEndLine) throws IOException {
		writeRange('-', aStartLine + 1, aEndLine - aStartLine);
		writeRange('+', bStartLine + 1, bEndLine - bStartLine);
	private void writeRange(final char prefix, final int begin, final int cnt)
			throws IOException {
	/**
	 * Write a standard patch script line.
	 *
	 * @param prefix
	 *            prefix before the line, typically '-', '+', ' '.
	 * @param text
	 *            the text object to obtain the line from.
	 * @param cur
	 *            line number to output.
	 * @throws IOException
	 *             the stream threw an exception while writing to it.
	 */
	protected void writeLine(final char prefix, final RawText text,
			final int cur) throws IOException {
	}

	/**
	 * Creates a {@link FileHeader} representing the given {@link DiffEntry}
	 * <p>
	 * This method does not use the OutputStream associated with this
	 * DiffFormatter instance. It is therefore safe to instantiate this
	 * DiffFormatter instance with a {@link DisabledOutputStream} if this method
	 * is the only one that will be used.
	 *
	 * @param ent
	 *            the DiffEntry to create the FileHeader for
	 * @return a FileHeader representing the DiffEntry. The FileHeader's buffer
	 *         will contain only the header of the diff output. It will also
	 *         contain one {@link HunkHeader}.
	 * @throws IOException
	 *             the stream threw an exception while writing to it, or one of
	 *             the blobs referenced by the DiffEntry could not be read.
	 * @throws CorruptObjectException
	 *             one of the blobs referenced by the DiffEntry is corrupt.
	 * @throws MissingObjectException
	 *             one of the blobs referenced by the DiffEntry is missing.
	 */
	public FileHeader createFileHeader(DiffEntry ent) throws IOException,
			CorruptObjectException, MissingObjectException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		final EditList editList;
		final FileHeader.PatchType type;

		writeDiffHeader(buf, ent);

		if (ent.getOldMode() == GITLINK || ent.getNewMode() == GITLINK) {
			writeGitLinkDiffText(buf, ent);
			editList = new EditList();
			type = PatchType.UNIFIED;
		} else {
			byte[] aRaw = open(ent.getOldMode(), ent.getOldId());
			byte[] bRaw = open(ent.getNewMode(), ent.getNewId());

			if (RawText.isBinary(aRaw) || RawText.isBinary(bRaw)) {
				buf.write(encodeASCII("Binary files differ\n"));
				editList = new EditList();
				type = PatchType.BINARY;
			} else {
				RawText a = rawTextFactory.create(aRaw);
				RawText b = rawTextFactory.create(bRaw);
				editList = new MyersDiff(a, b).getEdits();
				type = PatchType.UNIFIED;
			}
		}

		return new FileHeader(buf.toByteArray(), editList, type);