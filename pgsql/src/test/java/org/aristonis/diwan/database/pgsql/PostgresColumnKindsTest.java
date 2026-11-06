package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.schema.ForeignKeyInfo;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Column kinds and column metadata that only a PostgreSQL server can confirm. */
class PostgresColumnKindsTest {

    private Connection db;
    private Schema schema;

    @BeforeEach
    void createDatabase() {
        db = PostgresServer.newDatabase();
        schema = PostgresSchema.of(db);
    }

    private Object dataType(String table, String column) {
        return db.scalar("select data_type from information_schema.columns where table_name = ? and column_name = ?",
                table, column).get();
    }

    private Object precision(String table, String column) {
        return db.scalar("select datetime_precision from information_schema.columns where table_name = ?"
                + " and column_name = ?", table, column).get();
    }

    @Test
    void ipAndMacAddressColumnsUseTheirNativeTypesAndRefuseOtherText() {
        schema.create("hosts", t -> {
            t.id();
            t.ipAddress();
            t.macAddress();
            t.ipAddress("gateway").nullable();
        });

        assertEquals("inet", dataType("hosts", "ip_address"));
        assertEquals("macaddr", dataType("hosts", "mac_address"));
        assertEquals("inet", dataType("hosts", "gateway"));
        db.insert("insert into hosts (ip_address, mac_address) values (?::inet, ?::macaddr)", "10.0.0.1",
                "08:00:2b:01:02:03");
        assertThrows(QueryException.class, () -> db.insert(
                "insert into hosts (ip_address, mac_address) values ('not an address', '08:00:2b:01:02:03')"));
    }

    @Test
    void aStoredGeneratedColumnIsFilledByTheDatabaseAndRefusesWrites() {
        schema.create("items", t -> {
            t.id();
            t.integer("price");
            t.integer("double_price").storedAs("price * 2");
        });

        db.insert("insert into items (price) values (?)", 5);

        assertEquals(10, ((Number) db.scalar("select double_price from items").get()).intValue());
        assertThrows(QueryException.class, () -> db.insert("insert into items (price, double_price) values (1, 99)"));
    }

    @Test
    void aStoredGeneratedColumnCanBeAddedToAnExistingTableWithRows() {
        schema.create("items", t -> {
            t.id();
            t.integer("price");
        });
        db.insert("insert into items (price) values (?)", 4);

        schema.table("items", t -> t.integer("double_price").storedAs("price * 2"));

        assertEquals(8, ((Number) db.scalar("select double_price from items").get()).intValue());
    }

    @Test
    void columnCommentsAreWrittenOnCreateAndOnAdd() {
        schema.create("people", t -> {
            t.id();
            t.string("name").comment("full name");
        });
        schema.table("people", t -> t.string("nick").nullable().comment("what friends say"));

        assertEquals("full name", db.scalar("select col_description('people'::regclass, 2)").get());
        assertEquals("what friends say", db.scalar("select col_description('people'::regclass, 3)").get());
    }

    @Test
    void aChangedColumnTakesItsNewComment() {
        schema.create("people", t -> {
            t.id();
            t.string("name").comment("old");
        });

        schema.table("people", t -> t.string("name", 100).comment("new").change());

        assertEquals("new", db.scalar("select col_description('people'::regclass, 2)").get());
    }

    @Test
    void dateAndTimeColumnsKeepTheirPrecision() {
        schema.create("events", t -> {
            t.id();
            t.dateTime("a", 3);
            t.dateTimeTz("b", 0);
            t.timestamp("c", 6);
            t.time("d", 2);
        });

        assertEquals(3, ((Number) precision("events", "a")).intValue());
        assertEquals(0, ((Number) precision("events", "b")).intValue());
        assertEquals(6, ((Number) precision("events", "c")).intValue());
        assertEquals(2, ((Number) precision("events", "d")).intValue());
        assertEquals("timestamp without time zone", dataType("events", "a"));
        assertEquals("timestamp with time zone", dataType("events", "b"));
        assertEquals("time without time zone", dataType("events", "d"));
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
}
