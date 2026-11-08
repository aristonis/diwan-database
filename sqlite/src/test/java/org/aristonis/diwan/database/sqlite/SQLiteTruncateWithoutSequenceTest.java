package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class SQLiteTruncateWithoutSequenceTest {

    @TempDir
    Path folder;

    private Connection connection;
    private QueryFactory db;

    @BeforeEach
    void open() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("plain.db"));
        connection = Connection.builder().name("sqlite").dataSource(dataSource).build();
        db = SQLiteQuery.of(connection);
    }

    private long sequenceTables() {
        return connection.select("select name from sqlite_master where name = 'sqlite_sequence'").size();
    }

    @Test
    void truncateWorksWhenNoTableUsesAutoincrement() {
        connection.statement("create table plain (id integer primary key, label text)");
        db.table("plain").insert(Map.of("id", 1, "label", "a"));

        db.table("plain").truncate();

        assertEquals(0L, db.table("plain").count());
        assertEquals(0L, sequenceTables());
    }

    @Test
    void truncateManyWorksWhenNoTableUsesAutoincrement() {
        connection.statement("create table one (id integer primary key)");
        connection.statement("create table two (id integer primary key)");
        db.table("one").insert(Map.of("id", 1));
        db.table("two").insert(Map.of("id", 1));

        db.truncate(List.of("one", "two"));

        assertEquals(0L, db.table("one").count());
        assertEquals(0L, db.table("two").count());
    }

    @Test
    void truncateStillRestartsIdsWhenAutoincrementExists() {
        connection.statement("create table counted (id integer primary key autoincrement, label text)");
        db.table("counted").insert(Map.of("label", "a"));

        db.table("counted").truncate();

        assertEquals(1L, db.table("counted").insertGetId(Map.of("label", "again")));
    }

    @Test
    void truncateManyStillRestartsIdsWhenAutoincrementExists() {
        connection.statement("create table counted (id integer primary key autoincrement, label text)");
        connection.statement("create table plain (id integer primary key)");
        db.table("counted").insert(Map.of("label", "a"));

        db.truncate(List.of("counted", "plain"));

        assertEquals(1L, db.table("counted").insertGetId(Map.of("label", "again")));
    }
}
