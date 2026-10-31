package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.LoggedQuery;
import org.aristonis.diwan.database.QueryException;
import org.aristonis.diwan.database.UniqueConstraintViolationException;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class SQLiteSchemaTest {

    @TempDir
    Path folder;

    private Connection db;
    private Schema schema;

    @BeforeEach
    void createDatabase() {
        db = connection("");
        schema = SQLiteSchema.of(db);
    }

    private Connection connection(String prefix) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("test.db"));
        return Connection.builder().name("sqlite").tablePrefix(prefix).dataSource(dataSource).build();
    }

    /** Reads {@code pragma table_info} as column name to its row. */
    private Map<String, Map<String, Object>> columns(String table) {
        Map<String, Map<String, Object>> byName = new HashMap<>();
        for (Map<String, Object> row : db.select("select * from pragma_table_info(?)", table)) {
            byName.put((String) row.get("name"), row);
        }
        return byName;
    }

    private static String type(Map<String, Map<String, Object>> columns, String column) {
        return columns.get(column).get("type").toString().toLowerCase(java.util.Locale.ROOT);
    }

    private void createUsers() {
        schema.create("users", table -> {
            table.id();
            table.string("name");
            table.string("email").unique();
            table.text("bio").nullable();
            table.integer("age").defaultValue(18).unsigned().comment("years");
            table.bigInteger("views").defaultValue(0L);
            table.booleanColumn("active").defaultValue(true);
            table.decimal("balance", 10, 2).defaultValue(new java.math.BigDecimal("0.00"));
            table.date("born").nullable();
            table.timestamp("seen_at").useCurrent();
            table.json("meta").nullable();
            table.timestamps();
        });
    }

    @Test
    void createsEveryColumnTypeWithItsModifiers() {
        createUsers();

        Map<String, Map<String, Object>> columns = columns("users");
        assertEquals("integer", type(columns, "id"));
        assertEquals(1, ((Number) columns.get("id").get("pk")).intValue());
        assertEquals("varchar", type(columns, "name"));
        assertEquals(1, ((Number) columns.get("name").get("notnull")).intValue());
        assertEquals(0, ((Number) columns.get("bio").get("notnull")).intValue());
        assertEquals("'18'", columns.get("age").get("dflt_value"));
        assertEquals("tinyint(1)", type(columns, "active"));
        assertEquals("'1'", columns.get("active").get("dflt_value"));
        assertEquals("numeric", type(columns, "balance"));
        assertEquals("date", type(columns, "born"));
        assertEquals("datetime", type(columns, "seen_at"));
        assertEquals("CURRENT_TIMESTAMP", columns.get("seen_at").get("dflt_value"));
        assertEquals("text", type(columns, "meta"));
        assertEquals(0, ((Number) columns.get("created_at").get("notnull")).intValue());
        assertEquals(13, columns.size());
    }

    @Test
    void idCountsUpAndDefaultsAreApplied() {
        createUsers();

        db.insert("insert into users (name, email) values (?, ?)", "Ada", "ada@example.com");
        db.insert("insert into users (name, email) values (?, ?)", "Grace", "grace@example.com");

        List<Map<String, Object>> rows = db.select("select id, age, active, seen_at, bio from users order by id");
        assertEquals(Arrays.asList(1, 2), Arrays.asList(
                ((Number) rows.get(0).get("id")).intValue(), ((Number) rows.get(1).get("id")).intValue()));
        assertEquals(18, ((Number) rows.get(0).get("age")).intValue());
        assertEquals(1, ((Number) rows.get(0).get("active")).intValue());
        assertNotNull(rows.get(0).get("seen_at"));
        assertNull(rows.get(0).get("bio"));
    }

    @Test
    void uniqueColumnRejectsDuplicatesAndHasLaravelsIndexName() {
        createUsers();
        db.insert("insert into users (name, email) values (?, ?)", "Ada", "ada@example.com");

        assertThrows(UniqueConstraintViolationException.class,
                () -> db.insert("insert into users (name, email) values (?, ?)", "Eve", "ada@example.com"));
        assertEquals(1, ((Number) db.scalar(
                "select count(*) from sqlite_master where type = 'index' and name = ?", "users_email_unique")
                .get()).intValue());
    }

    @Test
    void tableAddsColumnsAndKeepsRows() {
        schema.create("posts", table -> table.string("title"));
        db.insert("insert into posts (title) values (?)", "Hello");

        schema.table("posts", table -> {
            table.integer("likes").defaultValue(0);
            table.text("body").nullable();
            table.string("slug").nullable().unique();
        });

        Map<String, Object> row = db.selectOne("select title, likes, body from posts").get();
        assertEquals("Hello", row.get("title"));
        assertEquals(0, ((Number) row.get("likes")).intValue());
        assertNull(row.get("body"));
        assertTrue(schema.hasColumn("posts", "slug"));
    }

    @Test
    void addingANotNullColumnWithoutDefaultToATableIsADatabaseError() {
        schema.create("posts", table -> table.string("title"));
        db.insert("insert into posts (title) values (?)", "Hello");

        assertThrows(QueryException.class, () -> schema.table("posts", table -> table.string("body")));
    }

    @Test
    void dropsRenamesAndChecksTables() {
        schema.create("a", table -> table.string("x"));

        assertTrue(schema.hasTable("a"));
        assertTrue(schema.hasColumn("a", "X"));
        assertFalse(schema.hasColumn("a", "y"));
        assertFalse(schema.hasColumn("missing", "x"));
        assertThrows(QueryException.class, () -> schema.create("a", table -> table.string("x")));

        schema.rename("a", "b");
        assertFalse(schema.hasTable("a"));
        assertTrue(schema.hasTable("b"));

        schema.drop("b");
        assertFalse(schema.hasTable("b"));
        assertThrows(QueryException.class, () -> schema.drop("b"));
        schema.dropIfExists("b");
    }

    @Test
    void everyOperationUsesTheTablePrefix() {
        Schema prefixed = SQLiteSchema.of(connection("app_"));

        prefixed.create("users", table -> table.string("email").unique());
        prefixed.rename("users", "people");

        assertTrue(schema.hasTable("app_people"));
        assertTrue(prefixed.hasTable("people"));
        assertTrue(prefixed.hasColumn("people", "email"));
        assertEquals(1, ((Number) db.scalar(
                "select count(*) from sqlite_master where type = 'index' and name = ?", "app_users_email_unique")
                .get()).intValue());
        prefixed.dropIfExists("people");
        assertFalse(schema.hasTable("app_people"));
    }

    @Test
    void pretendRecordsTheStatementsAndChangesNothing() {
        List<LoggedQuery> recorded = db.pretend(p -> SQLiteSchema.of(p).create("users", table -> {
            table.id();
            table.string("email").unique();
        }));

        List<String> sql = new ArrayList<>();
        for (LoggedQuery query : recorded) {
            sql.add(query.sql());
        }
        assertEquals(Arrays.asList(
                "create table \"users\" (\"id\" integer primary key autoincrement not null, \"email\" varchar not null)",
                "create unique index \"users_email_unique\" on \"users\" (\"email\")"), sql);
        assertFalse(schema.hasTable("users"));
    }

    @Test
    void hostileNamesAreOnlyNames() {
        String table = "t\"; drop table x; --";
        schema.create("x", t -> t.string("keep"));

        schema.create(table, t -> {
            t.string("select").defaultValue("it's \"quoted\"");
            t.string("from where");
        });
        db.insert("insert into \"t\"\"; drop table x; --\" (\"from where\") values (?)", "v");

        assertTrue(schema.hasTable("x"));
        assertTrue(schema.hasTable(table));
        assertTrue(schema.hasColumn(table, "SELECT"));
        assertEquals("it's \"quoted\"", db.scalar("select \"select\" from \"t\"\"; drop table x; --\"").get());
    }
}
