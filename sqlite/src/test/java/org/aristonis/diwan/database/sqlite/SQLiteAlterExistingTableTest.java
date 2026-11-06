package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.schema.ForeignKeyInfo;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** Alters that SQLite cannot do in place, so the table is rebuilt, and what the rebuild must keep. */
class SQLiteAlterExistingTableTest {

    @TempDir
    Path folder;

    private Connection db;
    private Schema schema;

    @BeforeEach
    void createDatabase() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("alter.db"));
        dataSource.setEnforceForeignKeys(true);
        db = Connection.builder().name("sqlite").dataSource(dataSource).build();
        schema = SQLiteSchema.of(db);
    }

    private long number(String sql) {
        return ((Number) db.scalar(sql).get()).longValue();
    }

    private Map<String, Object> column(String table, String name) {
        for (Map<String, Object> row : db.select("select * from pragma_table_info(?)", table)) {
            if (name.equals(row.get("name"))) {
                return row;
            }
        }
        throw new AssertionError("no column " + name + " in " + table);
    }

    @Test
    void aStoredGeneratedColumnAddedToAnExistingTableIsFilledForOldAndNewRows() {
        schema.create("items", t -> {
            t.id();
            t.integer("price");
        });
        db.insert("insert into items (price) values (?)", 5);

        schema.table("items", t -> t.integer("double_price").storedAs("price * 2"));
        db.insert("insert into items (price) values (?)", 7);

        assertEquals(10, number("select double_price from items where price = 5"));
        assertEquals(14, number("select double_price from items where price = 7"));
    }

    @Test
    void aStoredGeneratedColumnCanBeChangedToAnotherExpression() {
        schema.create("items", t -> {
            t.id();
            t.integer("price");
            t.integer("total").storedAs("price * 2");
        });
        db.insert("insert into items (price) values (?)", 5);

        schema.table("items", t -> t.integer("total").storedAs("price * 3").change());

        assertEquals(15, number("select total from items"));
        db.insert("insert into items (price) values (?)", 2);
        assertEquals(6, number("select total from items where price = 2"));
    }

    @Test
    void aPrimaryKeyAddedToAnExistingTableRefusesDuplicatesAndKeepsRows() {
        schema.create("tags", t -> {
            t.integer("id");
            t.string("name");
        });
        db.insert("insert into tags (id, name) values (?, ?)", 1, "php");

        schema.table("tags", t -> t.primary("id"));

        assertEquals(1, ((Number) column("tags", "id").get("pk")).intValue());
        assertEquals(1, number("select count(*) from tags"));
        assertThrows(QueryException.class, () -> db.insert("insert into tags (id, name) values (?, ?)", 1, "java"));
    }

    @Test
    void aPrimaryKeyDroppedFromAnExistingTableAllowsDuplicatesAndKeepsRows() {
        schema.create("tags", t -> {
            t.integer("id");
            t.string("name");
            t.primary("id");
        });
        db.insert("insert into tags (id, name) values (?, ?)", 1, "php");

        schema.table("tags", t -> t.dropPrimary());

        assertEquals(0, ((Number) column("tags", "id").get("pk")).intValue());
        db.insert("insert into tags (id, name) values (?, ?)", 1, "java");
        assertEquals(2, number("select count(*) from tags"));
    }

    @Test
    void changingCharAndDoubleColumnsKeepsTheirRowsAndAllowsNull() {
        schema.create("readings", t -> {
            t.charColumn("code", 4);
            t.doubleColumn("level");
        });
        db.insert("insert into readings (code, level) values (?, ?)", "ab", 1.5);

        schema.table("readings", t -> {
            t.charColumn("code", 8).nullable().change();
            t.doubleColumn("level").nullable().change();
        });

        assertEquals("ab", db.scalar("select code from readings").get());
        assertEquals(1.5, ((Number) db.scalar("select level from readings").get()).doubleValue());
        assertEquals(0, ((Number) column("readings", "code").get("notnull")).intValue());
        assertEquals(0, ((Number) column("readings", "level").get("notnull")).intValue());
        db.insert("insert into readings (code, level) values (null, null)");
    }

    @Test
    void aZeroDefaultSurvivesAChange() {
        schema.create("stock", t -> {
            t.id();
            t.integer("quantity").defaultValue(0);
        });

        schema.table("stock", t -> t.integer("quantity").defaultValue(0).nullable().change());
        db.insert("insert into stock default values");

        assertEquals(0, number("select quantity from stock"));
    }

    @Test
    void aZeroDefaultSurvivesARename() {
        schema.create("stock", t -> {
            t.id();
            t.integer("quantity").defaultValue(0);
        });

        schema.table("stock", t -> t.renameColumn("quantity", "amount"));
        db.insert("insert into stock default values");

        assertEquals(0, number("select amount from stock"));
    }

    @Test
    void renamingTimestampColumnsWithPrecisionKeepsTheRowsAndTheType() {
        schema.create("events", t -> {
            t.id();
            t.timestamp("starts_at", 6).nullable();
            t.dateTime("ends_at", 3).nullable();
        });
        db.insert("insert into events (starts_at, ends_at) values (?, ?)", "2026-01-02 03:04:05", "2026-01-02 04:00:00");

        schema.table("events", t -> {
            t.renameColumn("starts_at", "begins_at");
            t.renameColumn("ends_at", "finishes_at");
        });

        assertTrue(schema.hasColumn("events", "begins_at"));
        assertTrue(schema.hasColumn("events", "finishes_at"));
        assertFalse(schema.hasColumn("events", "starts_at"));
        assertEquals("2026-01-02 03:04:05", db.scalar("select begins_at from events").get());
        assertEquals("datetime", column("events", "begins_at").get("type").toString().toLowerCase());
    }

    @Test
    void anEnumStillRefusesOtherValuesAfterARebuild() {
        schema.create("orders", t -> {
            t.id();
            t.string("note");
            t.enumColumn("status", Arrays.asList("open", "shipped"));
        });
        db.insert("insert into orders (note, status) values (?, ?)", "first", "open");

        schema.table("orders", t -> t.string("note").nullable().change());

        db.insert("insert into orders (note, status) values (?, ?)", "second", "shipped");
        assertThrows(QueryException.class,
                () -> db.insert("insert into orders (note, status) values (?, ?)", "third", "lost"));
        assertEquals(2, number("select count(*) from orders"));
    }

    @Test
    void anEnumStillRefusesOtherValuesAfterARename() {
        schema.create("orders", t -> {
            t.id();
            t.enumColumn("status", Arrays.asList("open", "shipped"));
        });

        schema.table("orders", t -> t.renameColumn("status", "state"));

        db.insert("insert into orders (state) values (?)", "open");
        assertThrows(QueryException.class, () -> db.insert("insert into orders (state) values (?)", "lost"));
    }

    @Test
    void aCompositeForeignKeyIsReadBackAsOneKeyWithOrderedColumns() {
        schema.create("regions", t -> {
            t.integer("country");
            t.integer("code");
            t.primary("country", "code");
        });
        schema.create("cities", t -> {
            t.id();
            t.integer("region_country");
            t.integer("region_code");
            t.foreign("region_country", "region_code").references("country", "code").on("regions");
        });

        List<ForeignKeyInfo> keys = schema.foreignKeys("cities");

        assertEquals(1, keys.size());
        assertEquals(Arrays.asList("region_country", "region_code"), keys.get(0).columns());
        assertEquals("regions", keys.get(0).foreignTable());
        assertEquals(Arrays.asList("country", "code"), keys.get(0).foreignColumns());
    }

    @Test
    void withoutForeignKeyConstraintsLetsOrphanRowsInAndSwitchesEnforcementBackOn() {
        schema.create("users", t -> t.id());
        schema.create("posts", t -> {
            t.id();
            t.foreignId("user_id").constrained();
        });
        assertThrows(QueryException.class, () -> db.insert("insert into posts (user_id) values (?)", 9));

        schema.withoutForeignKeyConstraints(free -> free.connection().insert("insert into posts (user_id) values (?)", 9));

        assertEquals(1, number("select count(*) from posts"));
        assertThrows(QueryException.class, () -> db.insert("insert into posts (user_id) values (?)", 10));
    }

    @Test
    void withoutForeignKeyConstraintsUndoesItsWorkWhenItFails() {
        schema.create("users", t -> t.id());
        schema.create("posts", t -> {
            t.id();
            t.foreignId("user_id").constrained();
        });

        assertThrows(IllegalStateException.class, () -> schema.withoutForeignKeyConstraints(free -> {
            free.connection().insert("insert into posts (user_id) values (?)", 9);
            throw new IllegalStateException("stop");
        }));

        assertEquals(0, number("select count(*) from posts"));
        assertThrows(QueryException.class, () -> db.insert("insert into posts (user_id) values (?)", 10));
    }

    @Test
    void withoutForeignKeyConstraintsIsRefusedInsideATransaction() {
        assertThrows(IllegalStateException.class, () -> db.transaction(tx -> {
            SQLiteSchema.of(tx).withoutForeignKeyConstraints(free -> { });
            return null;
        }));
    }
}
