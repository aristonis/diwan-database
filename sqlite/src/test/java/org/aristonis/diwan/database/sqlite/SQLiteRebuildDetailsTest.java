package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.QueryException;
import org.aristonis.diwan.database.schema.Expression;
import org.aristonis.diwan.database.schema.IndexInfo;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** Details a table rebuild must keep, mined from other libraries' tests. */
class SQLiteRebuildDetailsTest {

    @TempDir
    Path folder;

    private Connection db;
    private Schema schema;
    private Schema old;

    @BeforeEach
    void connect() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("r.db"));
        dataSource.setEnforceForeignKeys(true);
        db = Connection.builder().name("sqlite").dataSource(dataSource).build();
        schema = SQLiteSchema.of(db);
        old = Schema.of(db, new SQLiteSchemaGrammar("3.22.0"));
    }

    private String defaultOf(String table, String column) {
        for (Map<String, Object> row : db.select("select name, dflt_value from pragma_table_info(?)", table)) {
            if (column.equals(row.get("name"))) {
                return String.valueOf(row.get("dflt_value"));
            }
        }
        throw new AssertionError(column);
    }

    private boolean hasIndex(String table, String name) {
        for (IndexInfo index : schema.indexes(table)) {
            if (index.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void untouchedDefaultsSurviveARebuildExactly() {
        schema.create("d", t -> {
            t.id();
            t.integer("zero").defaultValue(Expression.raw("0"));
            t.timestamp("at").useCurrent();
            t.string("quote").defaultValue("it's");
            t.string("num").defaultValue("123");
            t.integer("other").nullable();
        });
        String[] before = {defaultOf("d", "zero"), defaultOf("d", "at"), defaultOf("d", "quote"), defaultOf("d", "num")};

        schema.table("d", t -> t.bigInteger("other").nullable().change());
        old.table("d", t -> t.renameColumn("other", "renamed"));

        assertEquals(Arrays.asList(before), Arrays.asList(defaultOf("d", "zero"), defaultOf("d", "at"),
                defaultOf("d", "quote"), defaultOf("d", "num")));
        db.insert("insert into d (renamed) values (1)");
        Map<String, Object> row = db.select("select * from d").get(0);
        assertEquals(0L, ((Number) row.get("zero")).longValue());
        assertEquals("it's", row.get("quote"));
        assertEquals("123", row.get("num"));
        assertTrue(row.get("at") != null);
    }

    @Test
    void columnsNamedAfterKeywordsSurviveARebuild() {
        db.statement("create table k (id integer primary key, \"check\" text, \"create\" text, \"generated\" text,"
                + " \"as\" text, \"constraint\" text)");
        db.insert("insert into k values (1, 'a', 'b', 'c', 'd', 'e')");

        schema.table("k", t -> t.string("check").nullable().change());

        assertEquals("e", db.select("select \"constraint\" from k").get(0).get("constraint"));
    }

    @Test
    void severalDropsInOneCallKeepTheOtherKeys() {
        schema.create("users", t -> t.id());
        schema.create("teams", t -> t.id());
        schema.create("m", t -> {
            t.id();
            t.foreignId("user_id").constrained();
            t.foreignId("team_id").constrained();
            t.string("code").index();
            t.string("slug").unique();
        });

        old.table("m", t -> {
            t.dropIndex("m_code_index");
            t.dropForeign(Arrays.asList("user_id"));
            t.dropColumn("code");
        });

        assertEquals(1, schema.foreignKeys("m").size());
        assertEquals("teams", schema.foreignKeys("m").get(0).foreignTable());
        assertTrue(hasIndex("m", "m_slug_unique"));
        assertFalse(schema.hasColumn("m", "code"));
    }

    @Test
    void aTablePrefixKeepsTheReferencedTablesName() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("p.db"));
        dataSource.setEnforceForeignKeys(true);
        Connection prefixed = Connection.builder().name("p").tablePrefix("app_").dataSource(dataSource).build();
        Schema s = SQLiteSchema.of(prefixed);
        s.create("users", t -> t.id());
        s.create("posts", t -> {
            t.id();
            t.integer("user_id");
        });

        s.table("posts", t -> t.foreign("user_id").references("id").on("users"));

        assertEquals("app_users", s.foreignKeys("posts").get(0).foreignTable());
        assertThrows(QueryException.class, () -> prefixed.insert("insert into app_posts (user_id) values (9)"));
    }

    @Test
    void aMoneyColumnCanBeRenamedOnOldSqlite() {
        schema.create("ledger", t -> {
            t.id();
            t.money("debit", 2).asDecimal(12);
        });
        db.insert("insert into ledger (debit) values ('10.50')");

        old.table("ledger", t -> t.renameColumn("debit", "amount"));

        assertEquals("10.50", db.scalar("select amount from ledger").get());
        assertThrows(QueryException.class, () -> db.insert("insert into ledger (amount) values ('1e3')"));
    }
}
