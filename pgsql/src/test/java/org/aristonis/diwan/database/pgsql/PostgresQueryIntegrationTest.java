package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.values.Instants;
import org.aristonis.diwan.database.values.MoneyAmount;
import org.junit.jupiter.api.Test;

class PostgresQueryIntegrationTest {

    private static QueryFactory people() {
        Connection connection = PostgresServer.newDatabase();
        PostgresSchema.of(connection).create("people", t -> {
            t.id();
            t.string("name");
            t.integer("age");
        });
        QueryFactory db = PostgresQuery.of(connection);
        db.table("people").insert(List.of(
                Map.of("name", "Ann", "age", 30),
                Map.of("name", "Bob", "age", 40),
                Map.of("name", "Cid", "age", 50),
                Map.of("name", "dan", "age", 60)));
        return db;
    }

    private static List<Object> names(Builder query) {
        return query.orderBy("id").pluck("name");
    }

    @Test
    void readsAndAggregates() {
        QueryFactory db = people();

        assertEquals(List.of("Bob", "Cid"), names(db.table("people").whereBetween("age", List.of(35, 55))));
        assertEquals(List.of("Ann", "dan"), names(db.table("people").whereLike("name", "%N")));
        assertEquals(List.of(), names(db.table("people").whereLike("name", "%N", true)));
        assertEquals(List.of("Ann"), names(db.table("people").where("name", "ILIKE", "a%")));
        assertEquals(Map.of("name", "Cid"), db.table("people").select("name").where("age", 50).first().orElseThrow());

        Builder ordered = db.table("people").orderBy("age").limit(1).offset(1);
        assertEquals(4L, ordered.count());
        assertEquals(List.of("Bob"), ordered.pluck("name"));
        assertEquals(60, ((Number) db.table("people").max("age").orElseThrow()).intValue());
        assertEquals(180L, db.table("people").sum("age").longValue());
        assertTrue(db.table("people").where("age", ">", 55).exists());
    }

    @Test
    void insertGetIdReturnsTheNewKey() {
        QueryFactory db = people();

        Object id = db.table("people").insertGetId(Map.of("name", "Eve", "age", 70));

        assertEquals(5L, id);
        assertEquals("Eve", db.table("people").find(5L).orElseThrow().get("name"));
    }

    @Test
    void updateIncrementAndDeleteWithLimitTouchAtMostThatMany() {
        QueryFactory db = people();

        assertEquals(2, db.table("people").where("age", ">", 30).orderBy("age", "desc").limit(2)
                .update(Map.of("name", "old")));
        assertEquals(List.of("Ann", "Bob", "old", "old"), names(db.table("people")));
        assertEquals(1, db.table("people").where("name", "Ann").increment("age", 5));
        assertEquals(35, ((Number) db.table("people").where("name", "Ann").value("age").orElseThrow()).intValue());
        assertEquals(1, db.table("people").where("name", "old").orderBy("id").limit(1).delete());
        assertEquals(List.of("Ann", "Bob", "old"), names(db.table("people")));
    }

    @Test
    void truncateRestartsTheIdentity() {
        QueryFactory db = people();

        db.table("people").truncate();

        assertEquals(0L, db.table("people").count());
        assertEquals(1L, db.table("people").insertGetId(Map.of("name", "New", "age", 1)));
    }

    @Test
    void rawSqlRunsAndReturnsTheSameRows() {
        QueryFactory db = people();
        Builder query = db.table("people").where("name", "it's ?").orWhere("age", 40);

        String sql = query.toRawSql();

        assertEquals("select * from \"people\" where \"name\" = 'it''s ?' or \"age\" = 40", sql);
        assertEquals(1, db.connection().select(sql).size());
        assertEquals(1, db.connection().select(db.table("people").where("age", 40).toRawSql()).size());
    }

    @Test
    void moneyAndInstantsRoundTrip() {
        Connection connection = PostgresServer.newDatabase();
        PostgresSchema.of(connection).create("lines", t -> {
            t.id();
            t.money("cents", 2);
            t.money("exact", 2).asDecimal(12);
            t.string("text");
            t.instant("at");
        });
        QueryFactory db = PostgresQuery.of(connection);
        MoneyAmount price = MoneyAmount.of(new BigDecimal("10.1"), 2);
        Instant at = Instant.parse("2026-06-01T10:00:00.123456Z");

        db.table("lines").insert(Map.of("cents", price.toMinor(), "exact", price.amount(), "text", price.toText(),
                "at", at));

        Map<String, Object> row = db.table("lines").first().orElseThrow();
        assertEquals(price, MoneyAmount.read(row.get("cents"), 2));
        assertEquals(price, MoneyAmount.read(row.get("exact"), 2));
        assertEquals(price, MoneyAmount.read(row.get("text"), 2));
        assertEquals(at, Instants.read(row.get("at")));
        assertEquals(1L, db.table("lines").whereInstantBetween("at", at, at.plusMillis(1)).count());
        assertEquals(0L, db.table("lines").whereInstantBetween("at", at.plusMillis(1), at.plusSeconds(1)).count());
        assertEquals(1L, db.table("lines").whereDate("at", LocalDate.of(2026, 6, 1)).count());
        assertEquals(1L, db.table("lines").whereYear("at", 2026).whereMonth("at", 6).whereDay("at", 1).count());
    }

    @Test
    void aCloneChangesIndependently() {
        QueryFactory db = people();
        Builder original = db.table("people").where("age", ">", 30);

        Builder copy = original.clone().where("age", "<", 60);

        assertEquals(List.of("Bob", "Cid", "dan"), names(original));
        assertEquals(List.of("Bob", "Cid"), names(copy));
    }
}
