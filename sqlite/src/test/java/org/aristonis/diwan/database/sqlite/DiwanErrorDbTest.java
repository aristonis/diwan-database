package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.DiwanError;
import org.aristonis.diwan.database.exception.DriverException;
import org.aristonis.diwan.database.exception.QueryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class DiwanErrorDbTest {

    private static final String SECRET = "secret-value@example.com";

    @TempDir
    Path folder;

    private Connection db;

    @BeforeEach
    void createDatabase() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("errors.db"));
        dataSource.setEnforceForeignKeys(true);
        db = Connection.builder().name("sqlite").dataSource(dataSource).build();
    }

    private static DiwanError errorOf(QueryException failure) {
        return ((DriverException) failure.getCause()).error();
    }

    @Test
    void unique() {
        db.statement("create table users (email text unique)");
        db.insert("insert into users (email) values (?)", SECRET);

        QueryException failure = assertThrows(QueryException.class,
                () -> db.insert("insert into users (email) values (?)", SECRET));

        DiwanError error = errorOf(failure);
        assertEquals("DWN-CST-0001", error.code());
        assertFalse(error.key().isPresent());
        assertEquals(Arrays.asList("email"), error.columns());
        assertTrue(failure.getMessage().endsWith("[DWN-CST-0001 unique violation (email)]"));
    }

    @Test
    void foreignKey() {
        db.statement("create table parents (id integer primary key)");
        db.statement("create table kids (parent_id integer references parents (id))");

        QueryException failure = assertThrows(QueryException.class,
                () -> db.insert("insert into kids (parent_id) values (?)", 99));

        DiwanError error = errorOf(failure);
        assertEquals("DWN-CST-0002", error.code());
        assertFalse(error.key().isPresent());
        assertTrue(error.columns().isEmpty());
    }

    @Test
    void check() {
        db.statement("create table items (qty integer, constraint qty_positive check (qty > 0))");

        QueryException failure = assertThrows(QueryException.class,
                () -> db.insert("insert into items (qty) values (?)", -1));

        DiwanError error = errorOf(failure);
        assertEquals("DWN-CST-0003", error.code());
        assertEquals("qty_positive", error.key().orElseThrow());
        assertTrue(failure.getMessage().endsWith("[DWN-CST-0003 check violation: qty_positive]"));
    }

    @Test
    void notNull() {
        db.statement("create table people (name text not null)");

        QueryException failure = assertThrows(QueryException.class,
                () -> db.insert("insert into people (name) values (?)", (Object) null));

        DiwanError error = errorOf(failure);
        assertEquals("DWN-CST-0004", error.code());
        assertEquals(Arrays.asList("name"), error.columns());
    }

    @Test
    void noValuesLeak() {
        db.statement("create table users (email text unique)");
        db.insert("insert into users (email) values (?)", SECRET);

        QueryException failure = assertThrows(QueryException.class,
                () -> db.insert("insert into users (email) values (?)", SECRET));

        assertFalse(failure.getMessage().contains(SECRET));
        assertFalse(failure.getCause().getMessage().contains(SECRET));
        assertFalse(errorOf(failure).toString().contains(SECRET));
    }
}
