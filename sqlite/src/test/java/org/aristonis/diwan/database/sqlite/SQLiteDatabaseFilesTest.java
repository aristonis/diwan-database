package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Creating a database file from a template cleans up after a failed copy and spares a file it did not make. */
class SQLiteDatabaseFilesTest {

    @TempDir
    Path folder;

    @Test
    void aCopyThatFailsAfterWritingLeavesNoPartialFile() throws IOException {
        Path template = Files.write(folder.resolve("template.db"), "template".getBytes(StandardCharsets.UTF_8));
        Path target = folder.resolve("copy.db");

        UncheckedIOException error = assertThrows(UncheckedIOException.class,
                () -> SQLiteDatabaseFiles.create(target.toString(), template.toString(), (source, to) -> {
                    Files.write(to, new byte[] {1, 2});
                    throw new IOException("disk full");
                }));

        assertInstanceOf(IOException.class, error.getCause());
        assertFalse(Files.exists(target));
        assertTrue(Files.exists(template));
    }

    @Test
    void aTargetThatExistedBeforeIsKeptWhenTheCopyIsRefused() throws IOException {
        Path template = Files.write(folder.resolve("template.db"), "template".getBytes(StandardCharsets.UTF_8));
        Path target = Files.write(folder.resolve("copy.db"), "mine".getBytes(StandardCharsets.UTF_8));

        UncheckedIOException error = assertThrows(UncheckedIOException.class,
                () -> SQLiteDatabaseFiles.create(target.toString(), template.toString()));

        assertInstanceOf(FileAlreadyExistsException.class, error.getCause());
        assertArrayEquals("mine".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));
    }

    @Test
    void aSuccessfulCopyHasTheTemplateContent() throws IOException {
        Path template = Files.write(folder.resolve("template.db"), "template".getBytes(StandardCharsets.UTF_8));
        Path target = folder.resolve("copy.db");

        SQLiteDatabaseFiles.create(target.toString(), template.toString());

        assertEquals("template", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
    }
}
