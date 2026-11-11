package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class SQLiteCreateDatabaseTest {

    @TempDir
    Path folder;

    private Connection connection;
    private Schema schema;

    @BeforeEach
    void openMainDatabase() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("main.db"));
        connection = Connection.builder().name("sqlite").dataSource(dataSource).build();
        schema = SQLiteSchema.of(connection);
    }

    @Test
    void createsAndDropsFile() {
        Path file = folder.resolve("made.db");

        schema.createDatabase(file.toString());
        assertTrue(Files.isRegularFile(file));

        assertTrue(schema.dropDatabaseIfExists(file.toString()));
        assertFalse(Files.exists(file));
    }

    @Test
    void createsAnEmptyFile() throws IOException {
        Path file = folder.resolve("empty.db");

        schema.createDatabase(file.toString());

        assertEquals(0L, Files.size(file));
    }

    @Test
    void createFromTemplateCopiesTablesAndData() {
        Path template = folder.resolve("template.db");
        Path copy = folder.resolve("copy.db");
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + template);
        Connection templateConnection = Connection.builder().name("template").dataSource(source).build();
        templateConnection.statement("create table users (id integer primary key, name text)");
        templateConnection.insert("insert into users (id, name) values (?, ?)", 1, "ada");

        schema.createDatabase(copy.toString(), template.toString());

        SQLiteDataSource target = new SQLiteDataSource();
        target.setUrl("jdbc:sqlite:" + copy);
        Connection copyConnection = Connection.builder().name("copy").dataSource(target).build();
        Map<String, Object> row = copyConnection.selectOne("select name from users where id = ?", 1).orElseThrow();
        assertEquals("ada", row.get("name"));
    }

    @Test
    void templateCopySkipsLockFile() throws IOException {
        Path template = folder.resolve("template.db");
        Path lock = folder.resolve("template.db.migrate.lock");
        Files.createFile(template);
        Files.writeString(lock, "held");
        Path copy = folder.resolve("copy.db");

        schema.createDatabase(copy.toString(), template.toString());

        assertFalse(Files.exists(folder.resolve("copy.db.migrate.lock")));
        assertEquals("held", Files.readString(lock));
    }

    @Test
    void dropMissingReturnsFalse() {
        assertFalse(schema.dropDatabaseIfExists(folder.resolve("none.db").toString()));
    }

    @Test
    void forceIsIgnored() throws IOException {
        Path file = folder.resolve("forced.db");
        Files.createFile(file);

        assertTrue(schema.dropDatabaseIfExists(file.toString(), true));
        assertFalse(Files.exists(file));
    }

    @Test
    void memoryNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> schema.createDatabase(":memory:"));
        assertThrows(IllegalArgumentException.class, () -> schema.createDatabase("file::memory:"));
    }

    @Test
    void walSiblingsRemoved() throws IOException {
        Path file = folder.resolve("busy.db");
        Files.createFile(file);
        Path wal = folder.resolve("busy.db-wal");
        Path shm = folder.resolve("busy.db-shm");
        Path journal = folder.resolve("busy.db-journal");
        Files.createFile(wal);
        Files.createFile(shm);
        Files.createFile(journal);

        assertTrue(schema.dropDatabaseIfExists(file.toString()));

        assertFalse(Files.exists(wal));
        assertFalse(Files.exists(shm));
        assertFalse(Files.exists(journal));
    }

    @Test
    void dropLeavesTheLockFileAlone() throws IOException {
        Path file = folder.resolve("locked.db");
        Path lock = folder.resolve("locked.db.migrate.lock");
        Files.createFile(file);
        Files.createFile(lock);

        schema.dropDatabaseIfExists(file.toString());

        assertTrue(Files.exists(lock));
    }

    @Test
    void missingTemplateNamed() {
        String template = folder.resolve("absent.db").toString();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> schema.createDatabase(folder.resolve("x.db").toString(), template));

        assertTrue(error.getMessage().contains(template));
    }

    @Test
    void creatingAnExistingFileFails() throws IOException {
        Path file = folder.resolve("there.db");
        Files.createFile(file);

        assertThrows(UncheckedIOException.class, () -> schema.createDatabase(file.toString()));
    }
}
