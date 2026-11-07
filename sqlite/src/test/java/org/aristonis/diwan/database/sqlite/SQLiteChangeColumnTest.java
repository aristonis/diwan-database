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
import org.aristonis.diwan.database.LoggedQuery;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.schema.IndexInfo;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class SQLiteChangeColumnTest {

    @TempDir
    Path folder;

    private Connection db;
    private Schema schema;

    @BeforeEach
    void createShop() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("shop.db"));
        dataSource.setEnforceForeignKeys(true);
        db = Connection.builder().name("sqlite").dataSource(dataSource).build();
        schema = SQLiteSchema.of(db);
        schema.create("users", table -> {
            table.id();
            table.string("name", 50);
            table.text("bio").nullable();
            table.integer("points").defaultValue(0);
        });
        schema.create("orders", table -> {
            table.id();
            table.foreignId("user_id").constrained();
            table.enumColumn("status", Arrays.asList("new", "paid"));
            table.string("code").unique();
        });
        schema.create("lines", table -> {
            table.id();
            table.foreignId("order_id").constrained().cascadeOnDelete();
        });
        db.insert("insert into users (id, name, bio) values (1, 'ann', 'hi'), (2, 'bob', 'yo')");
        db.insert("insert into orders (id, user_id, status, code) values (1, 1, 'new', 'a'), (2, 2, 'paid', 'b')");
        db.insert("insert into lines (order_id) values (1), (1), (2)");
    }

    private Map<String, Object> column(String table, String name) {
        for (Map<String, Object> row : db.select("select * from pragma_table_info(?)", table)) {
            if (name.equals(row.get("name"))) {
                return row;
            }
        }
        throw new AssertionError("no column " + name);
    }

    private boolean hasIndex(String table, String name) {
        for (IndexInfo index : schema.indexes(table)) {
            if (index.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private long count(String sql) {
        return ((Number) db.scalar(sql).get()).longValue();
    }

    @Test
    void typeLengthNullableAndDefaultAreRedefinedAndRowsKept() {
        schema.table("users", table -> {
            table.string("name", 200).change();
            table.bigInteger("points").defaultValue(10).change();
            table.text("bio").nullable().defaultValue("none").change();
        });

        assertEquals("varchar", column("users", "name").get("type"));
        assertTrue("integer".equalsIgnoreCase((String) column("users", "points").get("type")));
        assertEquals("'10'", column("users", "points").get("dflt_value"));
        assertEquals(0, ((Number) column("users", "bio").get("notnull")).intValue());
        assertEquals("'none'", column("users", "bio").get("dflt_value"));
        assertEquals(2, count("select count(*) from users"));
        assertEquals("ann", db.scalar("select name from users where id = 1").get());
    }

    @Test
    void aNullableColumnChangedWithoutNullableBecomesNotNull() {
        schema.table("users", table -> table.text("bio").change());

        assertEquals(1, ((Number) column("users", "bio").get("notnull")).intValue());
        assertThrows(QueryException.class, () -> db.insert("insert into users (name) values ('cy')"));
    }

    @Test
    void enumValuesChangeAndTheCheckFollows() {
        schema.table("orders", table -> table.enumColumn("status", Arrays.asList("new", "paid", "refunded"))
                .defaultValue("new").change());

        db.update("update orders set status = 'refunded' where id = 2");
        assertThrows(QueryException.class, () -> db.update("update orders set status = 'lost' where id = 1"));
        assertEquals("'new'", column("orders", "status").get("dflt_value"));
    }

    @Test
    void uniqueFalseDropsAndUniqueAddsTheIndex() {
        schema.table("orders", table -> table.string("code").unique(false).change());
        assertFalse(hasIndex("orders", "orders_code_unique"));

        schema.table("users", table -> table.string("name", 50).unique().change());
        assertTrue(hasIndex("users", "users_name_unique"));
    }

    @Test
    void indexFalseDropsThePlainIndex() {
        schema.table("users", table -> table.index("name"));

        schema.table("users", table -> table.string("name", 50).index(false).change());

        assertFalse(hasIndex("users", "users_name_index"));
        assertEquals(2, count("select count(*) from users"));
    }

    @Test
    void primaryFalseDropsAndPrimaryMovesTheKey() {
        schema.create("tags", table -> {
            table.string("slug").primary();
            table.string("label");
        });
        db.insert("insert into tags (slug, label) values ('a', 'x'), ('b', 'y')");

        schema.table("tags", table -> {
            table.string("label").primary().change();
            table.string("slug").primary(false).change();
        });

        assertEquals(1, ((Number) column("tags", "label").get("pk")).intValue());
        assertEquals(0, ((Number) column("tags", "slug").get("pk")).intValue());
        assertEquals(2, count("select count(*) from tags"));
    }

    @Test
    void indexesKeysBothWaysAndTheSequenceSurvive() {
        db.delete("delete from lines where id = 3");
        schema.table("orders", table -> table.string("code", 20).change());

        assertTrue(hasIndex("orders", "orders_code_unique"));
        assertEquals(1, schema.foreignKeys("orders").size());
        assertThrows(QueryException.class,
                () -> db.insert("insert into orders (user_id, status, code) values (99, 'new', 'z')"));
        db.delete("delete from orders where id = 1");
        assertEquals(0, count("select count(*) from lines"));
        db.insert("insert into lines (order_id) values (2)");
        assertEquals(4, count("select max(id) from lines"));
    }

    @Test
    void changesMixedWithAnAddAndARenameRunInOneRebuild() {
        List<LoggedQuery> sql = schema.pretend(s -> s.table("users", table -> {
            table.string("name", 100).change();
            table.text("bio").nullable().comment("about").change();
            table.string("email").nullable();
            table.renameColumn("points", "score");
        }));
        long creates = sql.stream().filter(q -> q.sql().startsWith("create table")).count();
        assertEquals(1, creates);

        schema.table("users", table -> {
            table.string("name", 100).change();
            table.text("bio").nullable().comment("about").change();
            table.string("email").nullable();
            table.renameColumn("points", "score");
        });
        assertTrue(schema.hasColumn("users", "email"));
        assertTrue(schema.hasColumn("users", "score"));
        assertEquals(2, count("select count(*) from users"));
    }

    @Test
    void pretendListsTheRebuildAndChangesNothing() {
        List<LoggedQuery> sql = schema.pretend(s -> s.table("users", table -> table.text("bio").change()));

        assertFalse(sql.isEmpty());
        assertEquals(0, ((Number) column("users", "bio").get("notnull")).intValue());
    }

    @Test
    void aMissingColumnIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> schema.table("users", table -> table.string("ghost").change()));
    }

    @Test
    void theAutoIncrementColumnCannotBeChanged() {
        assertThrows(IllegalArgumentException.class,
                () -> schema.table("users", table -> table.bigInteger("id").change()));
    }

    @Test
    void rowsThatBreakTheNewDefinitionLeaveTheTableAsItWas() {
        db.update("update users set bio = null where id = 2");

        assertThrows(QueryException.class, () -> schema.table("users", table -> {
            table.string("name", 10).change();
            table.text("bio").change();
        }));

        assertEquals(0, ((Number) column("users", "bio").get("notnull")).intValue());
        assertEquals(2, count("select count(*) from users"));
        assertFalse(schema.hasTable("__temp__users"));
    }

    @Test
    void aRemovedEnumValueStillInUseFails() {
        assertThrows(QueryException.class, () -> schema.table("orders",
                table -> table.enumColumn("status", Arrays.asList("new")).change()));
        assertEquals(2, count("select count(*) from orders"));
    }

    @Test
    void aTableTheRebuildCannotCarryStaysRefused() {
        db.statement("create table notes (id integer primary key, body text, unique (body collate nocase))");

        assertThrows(UnsupportedOperationException.class,
                () -> schema.table("notes", table -> table.text("body").nullable().change()));
    }
}
