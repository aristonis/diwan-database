package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.Collections;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.values.Instants;
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
            t.integer("cents");
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
    void writesWithOrderAndHavingBindingsSendOnlyTheBindingsTheyWrite() {
        seedUsers();

        assertEquals(1, db.table("users").where("id", 1).orderByRaw("votes = ?", List.of(0))
                .update(row("born", "x")));
        assertEquals(1, db.table("users").where("id", 1).orderByRaw("votes = ?", List.of(0)).delete());
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
    void instantsRoundTrip() {
        Instant at = Instant.parse("2026-11-01T10:15:30.123456Z");

        db.table("lines").insert(row("cents", 1234, "at", at));
        Map<String, Object> stored = db.table("lines").first().orElseThrow();

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

    // ---- joins, subqueries, unions ----

    /** Users Ann(0) ann(1) Bob(2) a_c(3) abc*(4) Cy(5); posts: Ann 2, Bob 1, nobody else. */
    private void seedPosts() {
        seedUsers();
        Schema schema = SQLiteSchema.of(connection);
        schema.create("posts", t -> {
            t.id();
            t.integer("user_id");
            t.string("title");
            t.integer("stars").defaultValue(0);
        });
        db.table("posts").insert(Arrays.asList(row("user_id", 1, "title", "p1", "stars", 5),
                row("user_id", 1, "title", "p2", "stars", 1), row("user_id", 3, "title", "p3", "stars", 5)));
    }

    @Test
    void innerLeftRightAndCrossJoinsReturnTheirRows() {
        seedPosts();

        assertEquals(List.of("p1", "p2", "p3"), db.table("users").join("posts", "users.id", "=", "posts.user_id")
                .orderBy("posts.id").pluck("title"));
        assertEquals(7L, db.table("users").leftJoin("posts", "users.id", "=", "posts.user_id").count());
        assertEquals(4L, db.table("posts").rightJoin("users", "users.id", "=", "posts.user_id")
                .whereNull("posts.id").count(), "right join needs SQLite 3.39+, the bundled one is newer");
        assertEquals(18L, db.table("users").crossJoin("posts").count());
    }

    @Test
    void aJoinWithANestedOnAndAWhereFiltersInTheJoin() {
        seedPosts();

        List<Object> names = db.table("users")
                .join("posts", j -> j.on(o -> o.on("users.id", "=", "posts.user_id").orOn("users.votes", "=", "posts.stars"))
                        .where("posts.stars", 5))
                .orderBy("posts.id").orderBy("users.id").pluck("name");

        assertEquals(List.of("Ann", "Cy", "Bob", "Cy"), names, "Cy matches on votes = stars through the or");
    }

    @Test
    void joinSubFromSubAndSelectSubRun() {
        seedPosts();

        List<Object> names = db.table("users")
                .joinSub(db.table("posts").where("stars", 5), "top", "users.id", "=", "top.user_id")
                .orderBy("users.id").pluck("name");
        Builder counts = db.query().fromSub(db.table("posts").selectRaw("user_id, count(*) as n").groupBy("user_id"), "c")
                .where("n", ">", 1);
        Map<String, Object> ann = db.table("users").select("name")
                .selectSub(db.table("posts").selectRaw("count(*)").whereColumn("posts.user_id", "users.id"), "posts")
                .where("id", 1).first().orElseThrow();

        assertEquals(List.of("Ann", "Bob"), names);
        assertEquals(List.of(1), longsToInts(counts.pluck("user_id")));
        assertEquals(2, ((Number) ann.get("posts")).intValue());
    }

    @Test
    void whereInAndWhereExistsWithASubqueryFilter() {
        seedPosts();

        assertEquals(List.of("Ann", "Bob"), db.table("users")
                .whereIn("id", db.table("posts").select("user_id")).orderBy("id").pluck("name"));
        assertEquals(List.of("Ann"), db.table("users")
                .whereExists(q -> q.from("posts").whereColumn("posts.user_id", "users.id").where("title", "p2"))
                .pluck("name"));
    }

    @Test
    void unionAndUnionAllWithOrderAndLimitOnTheWholeUnion() {
        seedPosts();
        Builder first = db.table("users").select("name").where("votes", "<", 2);

        List<Object> union = db.table("users").select("name").where("votes", "<", 3)
                .union(first).orderByDesc("name").limit(2).pluck("name");
        long all = db.table("users").select("name").where("votes", "<", 3).unionAll(first).count();

        assertEquals(List.of("ann", "Bob"), union);
        assertEquals(5L, all);
    }

    @Test
    void unionPartsWithTheirOwnOrderAndLimitRun() {
        seedPosts();

        List<Object> names = db.table("users").select("name").orderBy("votes").limit(1)
                .unionAll(db.table("users").select("name").orderByDesc("votes").limit(1)).pluck("name");

        assertEquals(List.of("Ann", "Cy"), names);
    }

    @Test
    void countAndExistsOnAUnionSeeTheCombinedRows() {
        seedPosts();
        Builder union = db.table("users").select("name").where("votes", 0)
                .union(db.table("users").select("name").where("votes", 5));

        assertEquals(2L, union.count());
        assertTrue(union.exists());
        assertFalse(db.table("users").where("votes", 9).union(db.table("users").where("votes", 8)).exists());
        assertEquals(2, union.get().size(), "the builder is reusable after an aggregate");
    }

    @Test
    void orderingAUnionByASubqueryIsRefusedBySQLite() {
        seedPosts();
        Builder union = db.table("users").select("name").where("votes", 0)
                .union(db.table("users").select("name").where("votes", 5))
                .orderBy(db.table("users").selectRaw("max(votes)"), "asc");

        assertThrows(QueryException.class, union::get);
    }

    @Test
    void insertUsingCopiesAndInsertOrIgnoreUsingSkipsDuplicates() {
        seedPosts();
        SQLiteSchema.of(connection).create("names", t -> t.string("name").primary());

        int copied = db.table("names").insertUsing(List.of("name"), db.table("users").select("name").where("votes", "<", 2));
        int added = db.table("names").insertOrIgnoreUsing(List.of("name"), q -> q.from("users").select("name"));

        assertEquals(2, copied);
        assertEquals(4, added);
        assertEquals(6L, db.table("names").count());
    }

    @Test
    void updateWithASubqueryValueWritesTheSubqueryResult() {
        seedPosts();

        db.table("users").where("id", 1).update(row("votes",
                db.table("posts").selectRaw("sum(stars)").where("user_id", 1)));

        assertEquals(6, ((Number) db.table("users").where("id", 1).value("votes").orElseThrow()).intValue());
    }

    @Test
    void updateWithAJoinChangesOnlyTheJoinedRowsAndKeepsTheBuilder() {
        seedPosts();
        Builder starred = db.table("users").join("posts", j -> j.on("users.id", "=", "posts.user_id").where("posts.stars", 5))
                .where("users.votes", "<", 3);
        String before = starred.toSql();

        int changed = starred.update(row("votes", 100));

        assertEquals(2, changed);
        assertEquals(List.of("Ann", "Bob"), db.table("users").where("votes", 100).orderBy("id").pluck("name"));
        assertEquals(before, starred.toSql());
        assertEquals(0L, starred.count(), "the builder still runs; its votes < 3 now excludes both");
    }

    @Test
    void deleteWithAJoinRemovesOnlyTheJoinedRowsAndKeepsTheBuilder() {
        seedPosts();
        Builder bob = db.table("posts").join("users", "users.id", "=", "posts.user_id").where("users.name", "Bob");

        int deleted = bob.delete();

        assertEquals(1, deleted);
        assertEquals(List.of("p1", "p2"), db.table("posts").orderBy("id").pluck("title"));
        assertEquals(0L, bob.count(), "the builder still runs after the delete");
    }

    @Test
    void aJoinedUpdateOnAWithoutRowidTableFailsWithSQLitesError() {
        seedUsers();
        connection.statement("create table kv (k varchar primary key, v varchar) without rowid");
        db.table("kv").insert(row("k", "a", "v", "Ann"));
        Builder joined = db.table("kv").join("users", "users.name", "=", "kv.v");

        assertThrows(QueryException.class, () -> joined.update(row("v", "x")));
        assertThrows(QueryException.class, joined::delete);
        assertEquals("Ann", db.table("kv").value("v").orElseThrow());
    }

    @Test
    void theBindingOrderScenarioRunsForReal() {
        connection.statement("create table f (id integer, a varchar, g integer, h integer, w integer, z integer, f1 integer)");
        connection.statement("create table s (s1 integer)");
        connection.statement("create table j (id integer, j1 integer)");
        connection.statement("create table k (id integer, v integer)");
        connection.statement("create table w (id integer, w1 integer)");
        connection.statement("create table o (v integer, o1 integer)");
        connection.statement("create table u (a varchar, u1 integer)");
        db.table("f").insert(Arrays.asList(row("id", 1, "a", "f-a", "g", 1, "h", 10, "w", 1, "z", 6, "f1", 2),
                row("id", 2, "a", "f-miss", "g", 2, "h", 10, "w", 1, "z", 6, "f1", 0)));
        db.table("s").insert(row("s1", 1));
        db.table("j").insert(row("id", 1, "j1", 3));
        db.table("k").insert(row("id", 1, "v", 4));
        db.table("w").insert(row("id", 1, "w1", 5));
        db.table("o").insert(row("v", 1, "o1", 8));
        db.table("u").insert(Arrays.asList(row("a", "u-a", "u1", 9), row("a", "u-miss", "u1", 0)));

        Builder query = db.query()
                .select("a")
                .selectSub(db.table("s").selectRaw("count(*)").where("s1", 1), "c")
                .fromSub(db.table("f").where("f1", 2), "x")
                .joinSub(db.table("j").where("j1", 3), "y", "x.id", "=", "y.id")
                .join("k", j -> j.on("k.id", "=", "x.id").where("k.v", 4))
                .whereIn("w", db.table("w").select("id").where("w1", 5))
                .where("z", 6)
                .groupBy("g")
                .havingRaw("sum(h) > ?", Collections.singletonList(7))
                .orderBy(db.table("o").select("v").where("o1", 8).limit(1), "desc")
                .union(db.table("u").select("a", "u1").where("u1", 9))
                .orderBy("a", "asc");

        List<Map<String, Object>> rows = query.get();

        assertEquals(List.of("f-a", "u-a"), rows.stream().map(r -> r.get("a")).collect(java.util.stream.Collectors.toList()));
        assertEquals(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9), query.getBindings());
    }

    private static List<Integer> longsToInts(List<Object> values) {
        List<Integer> ints = new ArrayList<>();
        for (Object value : values) {
            ints.add(((Number) value).intValue());
        }
        return ints;
    }
}
