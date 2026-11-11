package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.DiwanError;
import org.aristonis.diwan.database.exception.DriverException;
import org.aristonis.diwan.database.exception.QueryException;
import org.junit.jupiter.api.Test;

class PostgresDiwanErrorTest {

    private static final String SECRET = "secret-value@example.com";

    private static DiwanError errorOf(QueryException failure) {
        return ((DriverException) failure.getCause()).error();
    }

    @Test
    void unique() {
        Connection db = PostgresServer.newDatabase();
        db.statement("create table users (email text)");
        db.statement("create unique index users_email_unique on users (email)");
        db.insert("insert into users (email) values (?)", SECRET);

        QueryException failure = assertThrows(QueryException.class,
                () -> db.insert("insert into users (email) values (?)", SECRET));

        DiwanError error = errorOf(failure);
        assertEquals("DWN-CST-0001", error.code());
        assertEquals("users_email_unique", error.key().orElseThrow());
        assertTrue(error.columns().isEmpty());
        assertEquals("users", error.table().orElseThrow());
        assertTrue(failure.getMessage().endsWith("[DWN-CST-0001 unique violation: users_email_unique]"));
    }

    @Test
    void foreignKey() {
        Connection db = PostgresServer.newDatabase();
        db.statement("create table parents (id int primary key)");
        db.statement("create table kids (parent_id int, constraint kids_parent_fk foreign key (parent_id)"
                + " references parents (id))");

        QueryException failure = assertThrows(QueryException.class,
                () -> db.insert("insert into kids (parent_id) values (?)", 99));

        DiwanError error = errorOf(failure);
        assertEquals("DWN-CST-0002", error.code());
        assertEquals("kids_parent_fk", error.key().orElseThrow());
    }

    @Test
    void foreignKeyOnParentDeleteNamesTheChildTable() {
        Connection db = PostgresServer.newDatabase();
        db.statement("create table parents (id int primary key)");
        db.statement("create table kids (parent_id int, constraint kids_parent_fk foreign key (parent_id)"
                + " references parents (id))");
        db.insert("insert into parents (id) values (1)");
        db.insert("insert into kids (parent_id) values (1)");

        QueryException failure = assertThrows(QueryException.class,
                () -> db.delete("delete from parents where id = ?", 1));

        DiwanError error = errorOf(failure);
        assertEquals("DWN-CST-0002", error.code());
        assertEquals("kids", error.table().orElseThrow());
    }

    @Test
    void check() {
        Connection db = PostgresServer.newDatabase();
        db.statement("create table items (qty int, constraint qty_positive check (qty > 0))");

        QueryException failure = assertThrows(QueryException.class,
                () -> db.insert("insert into items (qty) values (?)", -1));

        DiwanError error = errorOf(failure);
        assertEquals("DWN-CST-0003", error.code());
        assertEquals("qty_positive", error.key().orElseThrow());
        assertEquals("items", error.table().orElseThrow());
    }

    @Test
    void notNull() {
        Connection db = PostgresServer.newDatabase();
        db.statement("create table people (name text not null)");

        QueryException failure = assertThrows(QueryException.class,
                () -> db.insert("insert into people (name) values (null)"));

        DiwanError error = errorOf(failure);
        assertEquals("DWN-CST-0004", error.code());
        assertEquals(Arrays.asList("name"), error.columns());
        assertEquals("people", error.table().orElseThrow());
    }

    @Test
    void noValuesLeak() {
        Connection db = PostgresServer.newDatabase();
        db.statement("create table users (email text unique)");
        db.insert("insert into users (email) values (?)", SECRET);

        QueryException failure = assertThrows(QueryException.class,
                () -> db.insert("insert into users (email) values (?)", SECRET));

        assertFalse(failure.getMessage().contains(SECRET));
        assertFalse(failure.getCause().getMessage().contains(SECRET));
        assertFalse(errorOf(failure).toString().contains(SECRET));
    }
}
