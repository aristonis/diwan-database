package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.schema.IndexInfo;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

class PostgresNamesTest {

    private static boolean hasIndex(Schema schema, String table, String name) {
        for (IndexInfo index : schema.indexes(table)) {
            if (index.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aNonAsciiNameOverSixtyThreeBytesIsRefusedBeforeSql() {
        Schema schema = PostgresSchema.of(PostgresServer.newDatabase());
        schema.create("t", table -> table.integer("x"));
        String forty = "فهرس_العملاء_حسب_البريد_الالكتروني_والاسم";

        assertTrue(forty.length() < 63);
        assertThrows(IllegalArgumentException.class,
                () -> schema.table("t", table -> table.index(Arrays.asList("x"), forty)));
    }

    @Test
    void catalogReadsWorkInAMixedCaseSchema() {
        Connection admin = PostgresServer.newDatabase();
        admin.statement("create schema \"Tenant1\"");
        String database = String.valueOf(admin.scalar("select current_database()").get());
        PGSimpleDataSource source = PostgresServer.dataSource(database);
        source.setCurrentSchema("\"Tenant1\"");
        Schema schema = PostgresSchema.of(Connection.builder().name("pgsql").dataSource(source).build());

        schema.create("orders", table -> {
            table.id();
            table.string("code").unique();
        });

        assertTrue(schema.hasTable("orders"));
        assertTrue(schema.hasColumn("orders", "code"));
        assertTrue(hasIndex(schema, "orders", "orders_code_unique"));
        schema.table("orders", table -> table.dropPrimary());
        assertFalse(hasIndex(schema, "orders", "orders_pkey"));
    }

    @Test
    void reservedWordsWorkAsTableAndColumnNames() {
        Connection db = PostgresServer.newDatabase();
        Schema schema = PostgresSchema.of(db);
        schema.create("order", table -> {
            table.id();
            table.string("select").index();
            table.integer("user").nullable();
        });
        schema.table("order", table -> table.renameColumn("user", "group"));
        db.insert("insert into \"order\" (\"select\") values ('x')");

        assertTrue(schema.hasColumn("order", "group"));
        schema.table("order", table -> {
            table.dropIndex("order_select_index");
            table.dropColumn("select");
        });
        schema.drop("order");
        assertFalse(schema.hasTable("order"));
    }

    @Test
    void commentsTakeQuotesAndCanBeRemoved() {
        Connection db = PostgresServer.newDatabase();
        Schema schema = PostgresSchema.of(db);
        schema.create("c", table -> table.string("name").comment("the client's name"));
        String read = "select col_description('c'::regclass, 1)";
        assertEquals("the client's name", db.scalar(read).get());

        schema.table("c", table -> table.string("name").comment("").change());

        assertFalse(db.scalar(read).isPresent());
    }

    @Test
    void uniqueFalseOnChangeDropsTheConstraint() {
        Connection db = PostgresServer.newDatabase();
        Schema schema = PostgresSchema.of(db);
        schema.create("u", table -> table.string("email").unique());

        schema.table("u", table -> table.string("email").unique(false).change());

        assertFalse(hasIndex(schema, "u", "u_email_unique"));
        db.insert("insert into u values ('a'), ('a')");
    }
}
