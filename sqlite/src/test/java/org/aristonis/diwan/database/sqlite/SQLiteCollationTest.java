package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.LoggedQuery;
import org.aristonis.diwan.database.schema.Collation;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class SQLiteCollationTest {

    @TempDir
    Path folder;

    private Connection db;
    private Schema schema;

    @BeforeEach
    void open() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("collation.db"));
        dataSource.setEnforceForeignKeys(true);
        db = Connection.builder().name("sqlite").dataSource(dataSource).build();
        schema = SQLiteSchema.of(db);
        schema.create("owners", table -> table.id());
    }

    private long countNamed(String name) {
        return ((Number) db.scalar("select count(*) from t where name = ?", name).get()).longValue();
    }

    private String tableSql() {
        return db.scalar("select sql from sqlite_master where type = 'table' and name = 't'").get().toString();
    }

    @Test
    void collationIsWritten() {
        List<LoggedQuery> queries = schema.pretend(s -> s.create("t", table -> {
            table.id();
            table.string("name").nullable().collation("NOCASE");
        }));

        List<String> sql = new ArrayList<>();
        for (LoggedQuery query : queries) {
            sql.add(query.sql());
        }
        assertTrue(sql.get(0).contains("\"name\" varchar collate 'NOCASE'"), sql.get(0));
    }

    @Test
    void binaryOrderMatchesOnBothDatabases() {
        schema.create("t", table -> table.string("name").collation(Collation.BINARY));
        db.insert("insert into t (name) values ('Alpha'), ('Zulu'), ('alpha')");

        assertEquals(Arrays.<Object>asList("Alpha", "Zulu", "alpha"), SQLiteQuery.of(db).table("t").orderBy("name").pluck("name"));
    }

    @Test
    void changeSetsCollation() {
        schema.create("t", table -> table.string("name"));
        db.insert("insert into t (name) values ('a')");
        assertEquals(0, countNamed("A"));

        schema.table("t", table -> table.string("name").collation("NOCASE").change());

        assertTrue(tableSql().contains("collate 'NOCASE'"), tableSql());
        assertEquals(1, countNamed("A"));
    }

    @Test
    void rebuildKeepsCollation() {
        schema.create("t", table -> {
            table.string("name").collation("NOCASE");
            table.unsignedBigInteger("owner_id").nullable();
            table.string("other").nullable();
        });
        db.insert("insert into t (name) values ('a')");

        schema.table("t", table -> {
            table.foreign("owner_id").references("id").on("owners");
            table.dropColumn("other");
        });

        assertTrue(tableSql().contains("collate 'NOCASE'"), tableSql());
        assertEquals(1, countNamed("A"));
    }

    @Test
    void rawCollateIsCarried() {
        db.statement("create table t (id integer primary key, name text collate nocase, owner_id integer)");
        db.insert("insert into t (name) values ('a')");

        schema.table("t", table -> table.foreign("owner_id").references("id").on("owners"));

        assertTrue(tableSql().toLowerCase().contains("collate nocase"), tableSql());
        assertEquals(1, countNamed("A"));
    }

    @Test
    void tableConstraintCollateStillRefused() {
        db.statement("create table t (email text, owner_id integer, unique (email collate nocase))");

        assertThrows(UnsupportedOperationException.class,
                () -> schema.table("t", table -> table.foreign("owner_id").references("id").on("owners")));
    }

    @Test
    void rebuildKeepsCollateNextToACheck() {
        db.statement("create table t (id integer primary key, code text collate nocase check (length(code) > 0),"
                + " owner_id integer)");

        schema.table("t", table -> table.foreign("owner_id").references("id").on("owners"));

        String sql = tableSql().toLowerCase();
        assertTrue(sql.contains("collate nocase"), sql);
        assertTrue(sql.contains("check"), sql);
        assertThrows(RuntimeException.class, () -> db.insert("insert into t (code) values ('')"));
    }
}
