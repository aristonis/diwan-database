package org.aristonis.diwan.database.sqlite;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Creates and drops SQLite databases, which are plain files.
 *
 * <p>Creating makes an empty file, as Laravel's {@code SQLiteBuilder} does, or copies one template
 * file. Only that one file is copied: a sibling such as the {@code .migrate.lock} file of the
 * template is never read, copied or deleted. Dropping deletes the file and the {@code -wal},
 * {@code -shm} and {@code -journal} files SQLite keeps beside an open database.
 *
 * <p>A copy that fails part-way removes the partial file it made, so a later call never finds a
 * truncated database. A target that existed before the call is never touched.
 *
 * <p>The class holds no state; every method is safe to call from any thread.
 */
final class SQLiteDatabaseFiles {

    private static final List<String> SIDE_FILE_SUFFIXES =
            Collections.unmodifiableList(Arrays.asList("-wal", "-shm", "-journal"));

    private SQLiteDatabaseFiles() {
    }

    /**
     * Creates a database file, empty or as a copy of a template file.
     *
     * @param name the path of the new file, never {@code null}
     * @param template the path of the file to copy, or {@code null} for an empty database
     * @throws IllegalArgumentException if {@code name} is an in-memory spelling, is not a valid path, or
     *     {@code template} is not an existing file
     * @throws UncheckedIOException if the file exists already or cannot be written; a partial file this
     *     call made is deleted first
     */
    static void create(String name, String template) {
        create(name, template, Files::copy);
    }

    /**
     * Creates a database file with a given way of copying, so a test can make the copy fail.
     *
     * @param name the path of the new file, never {@code null}
     * @param template the path of the file to copy, or {@code null} for an empty database
     * @param copier copies the template to the target, never {@code null}
     * @throws IllegalArgumentException if {@code name} is an in-memory spelling, is not a valid path, or
     *     {@code template} is not an existing file
     * @throws UncheckedIOException if the file exists already or cannot be written
     */
    static void create(String name, String template, FileCopier copier) {
        if (SQLiteNames.isMemoryTarget(name)) {
            throw new IllegalArgumentException("Cannot create the SQLite database [" + name
                    + "]: an in-memory database has no file to create");
        }
        Path target = pathOf(name);
        try {
            if (template == null) {
                Files.createFile(target);
                return;
            }
            Path source = pathOf(template);
            if (!Files.isRegularFile(source)) {
                throw new IllegalArgumentException("Template database file [" + template + "] does not exist");
            }
            copyOrClean(copier, source, target);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the SQLite database file [" + name + "]: "
                    + e.getClass().getSimpleName(), e);
        }
    }

    private static void copyOrClean(FileCopier copier, Path source, Path target) throws IOException {
        try {
            copier.copy(source, target);
        } catch (FileAlreadyExistsException e) {
            // The target was there before this call, so it is not ours to delete.
            throw e;
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    /** Copies one file to a path that must not exist yet. */
    @FunctionalInterface
    interface FileCopier {

        /**
         * Copies a file.
         *
         * @param source the file to read, never {@code null}
         * @param target the path to create, never {@code null}
         * @throws IOException if the copy fails
         */
        void copy(Path source, Path target) throws IOException;
    }

    /**
     * Deletes a database file and its {@code -wal}, {@code -shm} and {@code -journal} files.
     *
     * @param name the path of the file, never {@code null}
     * @return {@code true} if the database file existed and was deleted, {@code false} if it was absent
     * @throws IllegalArgumentException if {@code name} is an in-memory spelling or not a valid path
     * @throws UncheckedIOException if a file exists but cannot be deleted
     */
    static boolean dropIfExists(String name) {
        if (SQLiteNames.isMemoryTarget(name)) {
            throw new IllegalArgumentException("Cannot drop the SQLite database [" + name
                    + "]: an in-memory database has no file to delete");
        }
        Path target = pathOf(name);
        try {
            boolean existed = Files.deleteIfExists(target);
            for (String suffix : SIDE_FILE_SUFFIXES) {
                Files.deleteIfExists(Paths.get(target + suffix));
            }
            return existed;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete the SQLite database file [" + name + "]: "
                    + e.getClass().getSimpleName(), e);
        }
    }

    private static Path pathOf(String text) {
        try {
            return Paths.get(text);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("[" + text + "] is not a valid file path", e);
        }
    }
}
