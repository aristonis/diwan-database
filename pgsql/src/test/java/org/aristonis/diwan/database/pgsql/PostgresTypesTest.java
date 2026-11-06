package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.Expression;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PostgresTypesTest {

    private Connection db;
    private Schema schema;

    @BeforeEach
    void connect() {
        db = PostgresServer.newDatabase();
        schema = PostgresSchema.of(db);
    }

    private Map<String, String> types(String table) {
        Map<String, String> found = new HashMap<>();
        for (Map<String, Object> row : db.select("select column_name, data_type, character_maximum_length,"
                + " is_nullable, column_default from information_schema.columns where table_name = ?", table)) {
            Object length = row.get("character_maximum_length");
            found.put(String.valueOf(row.get("column_name")),
                    row.get("data_type") + (length == null ? "" : "(" + length + ")"));
        }
        return found;
    }

    @Test
    void everyTypeIsCreatedWithLaravelsPostgresType() {
        schema.create("all_types", t -> {
            t.bigIncrements("id");
            t.string("s", 40);
            t.charColumn("c", 2);
            t.tinyText("tt");
            t.text("tx");
            t.mediumText("mt");
            t.longText("lt");
            t.integer("i");
            t.mediumInteger("mi");
            t.smallInteger("si");
            t.tinyInteger("ti");
            t.bigInteger("bi");
            t.year("y");
            t.booleanColumn("b");
            t.decimal("d", 10, 2);
            t.floatColumn("f");
            t.doubleColumn("db");
            t.date("dt");
            t.time("tm");
            t.timeTz("tmz");
            t.timestamp("ts");
            t.timestampTz("tsz");
            t.dateTime("dtm");
            t.dateTimeTz("dtmz");
            t.binary("bin");
            t.uuid("u");
            t.ipAddress("ip");
            t.macAddress("mac");
            t.json("js");
            t.jsonb("jsb");
            t.enumColumn("e", Arrays.asList("a", "b"));
            t.rawColumn("r", "numeric(5, 1) not null default 1.5");
        });

        Map<String, String> types = types("all_types");
        assertEquals("bigint", types.get("id"));
        assertEquals("character varying(40)", types.get("s"));
        assertEquals("character(2)", types.get("c"));
        assertEquals("character varying(255)", types.get("tt"));
        assertEquals("text", types.get("lt"));
        assertEquals("integer", types.get("mi"));
        assertEquals("smallint", types.get("ti"));
        assertEquals("integer", types.get("y"));
        assertEquals("boolean", types.get("b"));
        assertEquals("numeric", types.get("d"));
        assertEquals("double precision", types.get("f"));
        assertEquals("double precision", types.get("db"));
        assertEquals("time without time zone", types.get("tm"));
        assertEquals("time with time zone", types.get("tmz"));
        assertEquals("timestamp without time zone", types.get("dtm"));
        assertEquals("timestamp with time zone", types.get("tsz"));
        assertEquals("bytea", types.get("bin"));
        assertEquals("uuid", types.get("u"));
        assertEquals("inet", types.get("ip"));
        assertEquals("macaddr", types.get("mac"));
        assertEquals("jsonb", types.get("jsb"));
        assertEquals("character varying(255)", types.get("e"));
        assertEquals("numeric", types.get("r"));
    }

    @Test
    void enumRefusesAValueOutsideItsList() {
        schema.create("e", t -> t.enumColumn("side", Arrays.asList("debit", "credit")));

        db.insert("insert into e (side) values ('debit')");
        assertThrows(QueryException.class, () -> db.insert("insert into e (side) values ('x')"));
    }

    @Test
    void setIsRefused() {
        assertThrows(UnsupportedOperationException.class,
                () -> schema.create("s", t -> t.set("flags", Arrays.asList("a"))));
    }

    @Test
    void modifiersWorkAgainstTheDatabase() {
        schema.create("m", t -> {
            t.id();
            t.string("name").nullable();
            t.string("quote").defaultValue("it's");
            t.booleanColumn("active").defaultValue(true);
            t.timestamp("at").useCurrent();
            t.year("y").useCurrent();
            t.integer("qty").check("qty > 0").comment("how many");
            t.integer("total").storedAs("qty * 2");
        });

        db.insert("insert into m (qty) values (3)");
        Map<String, Object> row = db.select("select * from m").get(0);
        assertEquals("it's", row.get("quote"));
        assertEquals(Boolean.TRUE, row.get("active"));
        assertTrue(row.get("at") != null && row.get("y") != null);
        assertEquals(6, ((Number) row.get("total")).intValue());
        assertThrows(QueryException.class, () -> db.insert("insert into m (qty) values (0)"));
        assertEquals("how many", db.scalar("select col_description('m'::regclass, attnum) from pg_attribute"
                + " where attrelid = 'm'::regclass and attname = 'qty'").get());
    }

    @Test
    void virtualColumnsFollowTheServerVersion() {
        boolean isEighteen = ((Number) db.scalar("select current_setting('server_version_num')::int").get())
                .intValue() >= 180000;
        if (isEighteen) {
            schema.create("v", t -> {
                t.integer("a");
                t.integer("twice").virtualAs("a * 2");
            });
            db.insert("insert into v (a) values (4)");
            assertEquals(8, ((Number) db.scalar("select twice from v").get()).intValue());
        } else {
            assertThrows(UnsupportedOperationException.class, () -> schema.create("v", t -> {
                t.integer("a");
                t.integer("twice").virtualAs("a * 2");
            }));
        }
    }

    @Test
    void anExpressionDefaultIsWrittenAsGiven() {
        schema.create("x", t -> t.string("code").defaultValue(Expression.raw("'A' || 'B'")));

        db.insert("insert into x default values");
        assertEquals("AB", db.scalar("select code from x").get());
    }
}
