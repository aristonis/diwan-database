package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.values.Instants;
import org.aristonis.diwan.database.values.Money;
import org.aristonis.diwan.database.values.MoneyAmount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class SQLiteQueryIntegrationTest {

    @TempDir
    Path folder;

    private Connection connection;
    private QueryFactory db;

    @BeforeEach
    void createTables() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("query.db"));
        connection = Connection.builder().name("sqlite").dataSource(dataSource).build();
        db = SQLiteQuery.of(connection);
        Schema schema = SQLiteSchema.of(connection);
        schema.create("users", t -> {
            t.id();
            t.string("name");
            t.integer("votes").defaultValue(0);
            t.string("born").nullable();
        });
        schema.create("lines", t -> {
            t.id();
            t.money("cents", 2);
            t.instant("at");
        });
        schema.create("tokens", t -> {
            t.uuid("code").primary();
            t.string("label");
        });
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private void seedUsers() {
        List<Map<String, Object>> rows = new ArrayList<>();
        String[] names = {"Ann", "ann", "Bob", "a_c", "abc*", "Cy"};
        for (int i = 0; i < names.length; i++) {
            rows.add(row("name", names[i], "votes", i, "born", "2000-0" + (i + 1) + "-15 08:30:00"));
        }
        db.table("users").insert(rows);
    }

    private QueryFactory oldSQLite() {
        return QueryFactory.of(connection, new SQLiteQueryGrammar(""), new SQLiteProcessor(c -> "3.34.1"));
    }

    @Test
    void readsFilterOrderAndPick() {
        seedUsers();

        assertEquals(List.of("Cy", "abc*"), db.table("users").where("votes", ">=", 4).orderByDesc("votes")
                .pluck("name"));
        assertEquals("Bob", db.table("users").find(3).orElseThrow().get("name"));
        assertEquals("ann", db.table("users").whereIn("votes", List.of(1, 9)).value("name").orElseThrow());
    }

    @Test
    void countIgnoresOrderLimitAndOffset() {
        seedUsers();

        assertEquals(5L, db.table("users").where("votes", ">", 0).orderBy("name").limit(2).offset(1).count());
        assertEquals(15L, db.table("users").sum("votes").longValue());
    }

    @Test
    void insertsManyRowsInOneStatement() {
        seedUsers();

        assertEquals(6L, db.table("users").count());
    }

    @Test
    void insertGetIdUsesReturningOnACurrentSQLite() {
        Object first = db.table("users").insertGetId(row("name", "a"));
        Object second = db.table("users").insertGetId(row("name", "b"));

        assertEquals(1L, first);
        assertEquals(2L, second);
    }

    @Test
    void insertGetIdReturnsANonIntegerKeyWithReturning() {
        Object code = db.table("tokens").insertGetId(row("code", "k-1", "label", "x"), "code");

        assertEquals("k-1", code);
    }

    @Test
    void insertGetIdFallsBackToLastInsertRowidOnAnOldSQLite() {
        QueryFactory old = oldSQLite();

        assertEquals(1L, old.table("users").insertGetId(row("name", "a")));
        assertEquals(2L, old.table("users").insertGetId(row("name", "b")));
        assertEquals((Object) 3L, connection.transaction(tx -> QueryFactory.of(tx, new SQLiteQueryGrammar(""),
                new SQLiteProcessor(c -> "3.34.1")).table("users").insertGetId(row("name", "c"))));
    }

    @Test
    void theVersionIsComparedAsNumbers() {
        assertTrue(SQLiteProcessor.supportsReturning("3.35.0"));
        assertTrue(SQLiteProcessor.supportsReturning("3.100.0"));
        assertTrue(SQLiteProcessor.supportsReturning("4.0"));
        assertEquals(false, SQLiteProcessor.supportsReturning("3.9.9"));
        assertEquals(false, SQLiteProcessor.supportsReturning("3.34.1"));
        assertEquals(false, SQLiteProcessor.supportsReturning("garbage"));
    }

    @Test
    void updateIncrementAndDeleteRespectALimit() {
        seedUsers();

        assertEquals(2, db.table("users").orderBy("id").limit(2).update(row("born", null)));
        assertEquals(List.of(1, 2), db.table("users").whereNull("born").orderBy("id").pluck("id"));
        assertEquals(1, db.table("users").orderByDesc("id").limit(1).increment("votes", 10));
        assertEquals(15L, ((Number) db.table("users").find(6).orElseThrow().get("votes")).longValue());
        assertEquals(3, db.table("users").where("votes", "<", 10).orderBy("votes").limit(3).delete());
        assertEquals(List.of("a_c", "abc*", "Cy"), db.table("users").orderBy("id").pluck("name"));
    }

    @Test
    void truncateEmptiesTheTableAndResetsIds() {
        seedUsers();

        db.table("users").truncate();

        assertEquals(0L, db.table("users").count());
        assertEquals(1L, db.table("users").insertGetId(row("name", "again")));
    }

    @Test
    void rawSqlRunsAndReturnsTheSameRows() {
        seedUsers();
        Builder query = db.table("users").where("name", "' or 1=1 --").orWhere("votes", true);

        assertEquals(query.get(), connection.select(query.toRawSql()));
        assertEquals(1, query.get().size());
    }

    @Test
    void caseSensitiveLikeMatchesCaseAndLiteralGlobCharacters() {
        seedUsers();

        assertEquals(List.of("ann"), db.table("users").whereLike("name", "a%n", true).pluck("name"));
        assertEquals(List.of("Ann", "ann"), db.table("users").whereLike("name", "a%n").orderBy("id").pluck("name"));
        assertEquals(List.of("abc*"), db.table("users").whereLike("name", "abc*", true).pluck("name"));
        assertEquals(List.of("a_c", "abc*"), db.table("users").whereLike("name", "a_c%", true).orderBy("id")
                .pluck("name"));
    }

    @Test
    void caseSensitiveLikeTreatsABracketAsLiteral() {
        db.table("users").insert(List.of(row("name", "[a]"), row("name", "a")));

        assertEquals(List.of("[a]"), db.table("users").whereLike("name", "[a]", true).pluck("name"));
        assertEquals(List.of("[a]"), db.table("users").whereLike("name", "[a]%", true).pluck("name"));
    }

    @Test
    void dateWheresFilterTextDates() {
        seedUsers();

        assertEquals(List.of("Bob"), db.table("users").whereMonth("born", 3).pluck("name"));
        assertEquals(List.of("Bob"), db.table("users").whereDate("born", "2000-03-15").pluck("name"));
        assertEquals(6L, db.table("users").whereYear("born", 2000).whereDay("born", 15).whereTime("born", "08:30:00")
                .count());
    }

    @Test
    void nullSafeEqualsMatchesNull() {
        seedUsers();
        db.table("users").where("id", 1).update(row("born", null));

        assertEquals(List.of(1), db.table("users").whereNullSafeEquals("born", null).pluck("id"));
    }

    @Test
    void moneyAndInstantsRoundTrip() {
        Instant at = Instant.parse("2026-11-01T10:15:30.123456Z");
        BigDecimal amount = new BigDecimal("12.34");

        db.table("lines").insert(row("cents", Money.toMinor(amount, 2), "at", at));
        Map<String, Object> stored = db.table("lines").first().orElseThrow();

        assertEquals(MoneyAmount.of(amount, 2), MoneyAmount.read(stored.get("cents"), 2));
        assertEquals(at, Instants.read(stored.get("at")));
        assertEquals(Instants.toMicros(at), stored.get("at"));
    }

    @Test
    void whereInstantBetweenIncludesTheStartAndExcludesTheEnd() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-02-01T00:00:00Z");
        db.table("lines").insert(List.of(row("cents", 1, "at", start), row("cents", 2, "at", end.minusNanos(1000)),
                row("cents", 3, "at", end)));

        assertEquals(List.of(1, 2), db.table("lines").whereInstantBetween("at", start, end).orderBy("id")
                .pluck("cents"));
        db.table("lines").where("at", "<", end).update(row("at", end));
        assertEquals(3L, db.table("lines").where("at", end).count());
    }

    @Test
    void aCloneIsIndependent() {
        seedUsers();
        Builder base = db.table("users").where("votes", ">", 1);
        Builder copy = base.clone().where("name", "Cy");

        assertEquals(4L, base.count());
        assertEquals(1L, copy.count());
        assertNotEquals(base.toSql(), copy.toSql());
    }
}
