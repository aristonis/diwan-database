package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.Test;

class PostgresTruncateManyTest {

    private static QueryFactory invoices() {
        Connection connection = PostgresServer.newDatabase();
        connection.statement("create table invoices (id serial primary key, label text)");
        connection.statement("create table lines (id serial primary key,"
                + " invoice_id integer not null references invoices (id))");
        QueryFactory db = PostgresQuery.of(connection);
        db.table("invoices").insert(Map.of("label", "a"));
        db.table("lines").insert(Map.of("invoice_id", 1));
        return db;
    }

    @Test
    void oneStatementEmptiesBothTablesAndRestartsIds() {
        QueryFactory db = invoices();

        db.truncate(List.of("invoices", "lines"));

        assertEquals(0L, db.table("invoices").count());
        assertEquals(0L, db.table("lines").count());
        assertEquals(1L, db.table("invoices").insertGetId(Map.of("label", "again")));
    }

    @Test
    void cascadeEmptiesTheReferringTableToo() {
        QueryFactory db = invoices();

        db.truncate(List.of("invoices"), true);

        assertEquals(0L, db.table("lines").count());
    }

    @Test
    void withoutCascadeTheDatabaseErrorSurfaces() {
        QueryFactory db = invoices();

        assertThrows(QueryException.class, () -> db.truncate(List.of("invoices")));

        assertEquals(1L, db.table("invoices").count());
    }

    @Test
    void anEmptyListRunsNothing() {
        QueryFactory db = invoices();

        db.truncate(List.of());

        assertEquals(1L, db.table("invoices").count());
    }
}
