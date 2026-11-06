package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Year;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class SQLiteTypesTest {

    @TempDir
    Path folder;

    private Connection db;
    private Schema schema;

    @BeforeEach
    void createDatabase() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("types.db"));
        db = Connection.builder().name("sqlite").dataSource(dataSource).build();
        schema = SQLiteSchema.of(db);
    }

    private Map<String, String> types(String table) {
        Map<String, String> types = new HashMap<>();
        for (Map<String, Object> row : db.select("select name, type from pragma_table_info(?)", table)) {
            types.put(row.get("name").toString(), row.get("type").toString().toLowerCase(Locale.ROOT));
        }
        return types;
    }

    @Test
    void everyTypeHasLaravelsSqliteType() {
        schema.create("everything", t -> {
            t.tinyInteger("a");
            t.smallInteger("b");
            t.mediumInteger("c");
            t.unsignedBigInteger("d");
            t.floatColumn("e");
            t.doubleColumn("f");
            t.charColumn("g", 2);
            t.tinyText("h");
            t.mediumText("i");
            t.longText("j");
            t.dateTime("k");
            t.dateTimeTz("l", 3);
            t.time("m");
            t.timeTz("n");
            t.timestampTz("o");
            t.year("p");
            t.binary("q");
            t.uuid();
            t.ulid();
            t.ipAddress();
            t.macAddress();
            t.jsonb("r");
            t.enumColumn("s", Arrays.asList("x", "y"));
        });

        Map<String, String> types = types("everything");
        assertEquals("integer", types.get("a"));
        assertEquals("integer", types.get("c"));
        assertEquals("integer", types.get("d"));
        assertEquals("float", types.get("e"));
        assertEquals("double", types.get("f"));
        assertEquals("varchar", types.get("g"));
        assertEquals("text", types.get("j"));
        assertEquals("datetime", types.get("k"));
        assertEquals("datetime", types.get("l"));
        assertEquals("time", types.get("n"));
        assertEquals("datetime", types.get("o"));
        assertEquals("integer", types.get("p"));
        assertEquals("blob", types.get("q"));
        assertEquals("varchar", types.get("uuid"));
        assertEquals("varchar", types.get("ulid"));
        assertEquals("varchar", types.get("ip_address"));
        assertEquals("varchar", types.get("mac_address"));
        assertEquals("text", types.get("r"));
        assertEquals("varchar", types.get("s"));
    }

    @Test
    void incrementsAreAutoIncrementKeys() {
        schema.create("small", t -> {
            t.smallIncrements("id");
            t.string("name");
        });
        db.insert("insert into small (name) values ('a'), ('b')");

        assertEquals(2L, ((Number) db.scalar("select max(id) from small").get()).longValue());
        assertTrue(db.scalar("select sql from sqlite_master where name = 'small'").get().toString()
                .contains("primary key autoincrement"));
    }

    @Test
    void currentDefaultsAreFilledIn() {
        schema.create("stamps", t -> {
            t.integer("n");
            t.year("y").useCurrent();
            t.time("at").useCurrent();
            t.dateTime("dt").useCurrent();
        });
        db.insert("insert into stamps (n) values (1)");

        Map<String, Object> row = db.selectOne("select * from stamps").get();
        assertEquals((long) Year.now(java.time.ZoneOffset.UTC).getValue(), ((Number) row.get("y")).longValue());
        assertTrue(row.get("at").toString().matches("\\d\\d:\\d\\d:\\d\\d"));
        assertTrue(row.get("dt").toString().startsWith(String.valueOf(Year.now(java.time.ZoneOffset.UTC).getValue())));
    }

    @Test
    void enumRefusesOtherValuesAndKeepsHostileOnesLiteral() {
        schema.create("users", t -> t.enumColumn("status", Arrays.asList("active", "o'k", "x') or 1=1 --")));

        db.insert("insert into users (status) values (?)", "o'k");
        db.insert("insert into users (status) values (?)", "x') or 1=1 --");

        assertThrows(QueryException.class, () -> db.insert("insert into users (status) values ('other')"));
        assertEquals(2L, ((Number) db.scalar("select count(*) from users").get()).longValue());
    }

    @Test
    void setIsRefusedOnSqlite() {
        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> schema.create("tags", t -> t.set("flags", Arrays.asList("a", "b"))));
        assertTrue(thrown.getMessage().contains("SET"));
    }

    @Test
    void enumCanBeAddedToAnExistingTable() {
        schema.create("users", t -> t.id());
        schema.table("users", t -> t.enumColumn("role", Arrays.asList("admin", "member")).nullable());

        db.insert("insert into users (role) values ('admin')");
        assertThrows(QueryException.class, () -> db.insert("insert into users (role) values ('guest')"));
    }
}
