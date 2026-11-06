package org.aristonis.diwan.database.query;

import org.aristonis.diwan.database.Expression;
import static org.aristonis.diwan.database.query.RecordingConnection.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BuilderExecutionTest {

    private final RecordingConnection db = new RecordingConnection();

    /** A grammar that prepares bindings the way a database module would, to prove every path uses it. */
    private static final class InstantGrammar extends QueryGrammar {
        InstantGrammar() {
            super("");
        }

        @Override
        public List<Object> prepareBindings(List<?> bindings) {
            List<Object> out = new java.util.ArrayList<>();
            for (Object value : bindings) {
                out.add(value instanceof Instant ? ((Instant) value).toEpochMilli() : value);
            }
            return out;
        }
    }

    private QueryFactory preparing() {
        return QueryFactory.of(db.connection, new InstantGrammar(), new Processor());
    }

    @Test
    void getRunsTheSelectWithItsBindings() {
        db.returns("select * from \"t\" where \"a\" = ?", List.of(row("a", 1)));

        List<Map<String, Object>> rows = db.db().table("t").where("a", 1).get();

        assertEquals(List.of(row("a", 1)), rows);
        assertEquals(new RecordingConnection.Call("select", "select * from \"t\" where \"a\" = ?", List.of(1)),
                db.last());
    }

    @Test
    void firstFindAndValueLimitAClone() {
        db.returns("select * from \"t\" where \"a\" = ? limit 1", List.of(row("a", 1, "b", 2)));
        db.returns("select * from \"t\" where \"id\" = ? limit 1", List.of());
        db.returns("select \"b\" from \"t\" limit 1", List.of(row("b", 7)));
        Builder query = db.db().table("t").where("a", 1);

        assertEquals(Optional.of(row("a", 1, "b", 2)), query.first());
        assertEquals(Optional.empty(), db.db().table("t").find(5));
        assertEquals(Optional.of(7), db.db().table("t").value("b"));
        assertEquals("select * from \"t\" where \"a\" = ?", query.toSql());
    }

    @Test
    void pluckReturnsValuesOrAKeyedMap() {
        db.returns("select \"name\" from \"t\"", List.of(row("name", "a"), row("name", null)));
        db.returns("select \"t\".\"name\", \"id\" as \"key\" from \"t\"",
                List.of(row("name", "a", "key", 1), row("name", "b", "key", 2)));

        assertEquals(java.util.Arrays.asList("a", null), db.db().table("t").pluck("name"));
        Map<Object, Object> keyed = db.db().table("t").pluck("t.name", "id as key");
        assertEquals(List.of(1, 2), List.copyOf(keyed.keySet()));
        assertEquals(List.of("a", "b"), List.copyOf(keyed.values()));
    }

    @Test
    void existsReadsTheExistsColumn() {
        db.returns("select exists(select * from \"t\") as \"exists\"", List.of(row("exists", 1)));

        assertTrue(db.db().table("t").exists());
        assertFalse(db.db().table("t").doesntExist());
        assertFalse(db.db().table("u").exists());
    }

    @Test
    void countDropsColumnsOrdersLimitAndOffsetOnlyOnAClone() {
        db.returns("select count(*) as \"aggregate\" from \"t\" where \"a\" = ?", List.of(row("AGGREGATE", 42L)));
        Builder query = db.db().table("t").selectRaw("x + ?", List.of(9)).where("a", 1)
                .orderByRaw("f(?)", List.of(8)).limit(5).offset(10);
        String before = query.toSql();

        assertEquals(42L, query.count());

        assertEquals(List.of(1), db.last().bindings());
        assertEquals(before, query.toSql());
        assertEquals(List.of(9, 1, 8), query.getBindings());
    }

    @Test
    void aggregateLeavesTheBuilderUntouchedWhenTheQueryFails() {
        db.failsWith(new IllegalStateException("boom"));
        Builder query = db.db().table("t").select("a").orderBy("a").limit(3);

        assertThrows(IllegalStateException.class, query::count);

        assertEquals("select \"a\" from \"t\" order by \"a\" asc limit 3", query.toSql());
    }

    @Test
    void countWithHavingsRunsOverASubquery() {
        db.returns("select count(*) as \"aggregate\" from (select \"a\" from \"t\" group by \"a\" having \"a\" > ?)"
                + " as \"temp_table\"", List.of(row("aggregate", 3)));

        assertEquals(3L, db.db().table("t").select("a").groupBy("a").having("a", ">", 1).count());
    }

    @Test
    void minMaxSumAndAvg() {
        db.returns("select min(\"p\") as \"aggregate\" from \"t\"", List.of(row("aggregate", 1)));
        db.returns("select max(\"p\") as \"aggregate\" from \"t\"", List.of(row("aggregate", null)));
        db.returns("select sum(\"p\") as \"aggregate\" from \"t\"", List.of(row("aggregate", "12.50")));
        db.returns("select avg(\"p\") as \"aggregate\" from \"t\"", List.of(row("aggregate", 2.5)));

        assertEquals(Optional.of(1), db.db().table("t").min("p"));
        assertEquals(Optional.empty(), db.db().table("t").max("p"));
        assertEquals(new BigDecimal("12.50"), db.db().table("t").sum("p"));
        assertEquals(Optional.of(2.5), db.db().table("t").avg("p"));
        assertEquals(Optional.of(2.5), db.db().table("t").average("p"));
        assertEquals(0L, db.db().table("u").sum("p"));
        assertEquals(0L, db.db().table("u").count());
    }

    @Test
    void insertBindsRowsInColumnOrderAndSkipsExpressions() {
        assertTrue(db.db().table("t").insert(List.of(row("b", 1, "a", 2), row("a", 3, "b", Expression.raw("now()")))));

        assertEquals(new RecordingConnection.Call("insert", "insert into \"t\" (\"a\", \"b\") values (?, ?), (?, now())",
                List.of(2, 1, 3)), db.last());
        assertTrue(db.db().table("t").insert(row("b", 1, "a", 2)));
        assertEquals(List.of(1, 2), db.last().bindings());
    }

    @Test
    void anEmptyInsertRunsNothing() {
        assertTrue(db.db().table("t").insert(List.of()));

        assertTrue(db.calls.isEmpty());
    }

    @Test
    void insertGetIdIsLeftToTheDatabaseProcessor() {
        assertThrows(UnsupportedOperationException.class, () -> db.db().table("t").insertGetId(row("a", 1)));
    }

    @Test
    void insertGetIdHandsThePreparedStatementToTheProcessor() {
        Processor processor = new Processor() {
            @Override
            public Object processInsertGetId(Builder query, String sql, List<Object> values, String sequence) {
                return sql + values + sequence;
            }
        };
        QueryFactory factory = QueryFactory.of(db.connection, new InstantGrammar(), processor);

        Object id = factory.table("t").insertGetId(row("at", Instant.ofEpochMilli(5), "n", Expression.raw("1")), "key");

        assertEquals("insert into \"t\" (\"at\", \"n\") values (?, 1)[5]key", id);
    }

    @Test
    void updatePutsValuesBeforeWheresAndPreparesThem() {
        db.affects(3);

        int count = preparing().table("t").where("id", Instant.ofEpochMilli(9))
                .update(row("at", Instant.ofEpochMilli(7), "n", Expression.raw("n + 1")));

        assertEquals(3, count);
        assertEquals(new RecordingConnection.Call("update", "update \"t\" set \"at\" = ?, \"n\" = n + 1 where \"id\" = ?",
                List.of(7L, 9L)), db.last());
    }

    @Test
    void everyReadPathPreparesBindings() {
        preparing().table("t").whereRaw("a = ?", List.of(Instant.ofEpochMilli(1))).get();
        assertEquals(List.of(1L), db.last().bindings());
        preparing().table("t").where("a", Instant.ofEpochMilli(2)).exists();
        assertEquals(List.of(2L), db.last().bindings());
        preparing().table("t").where("a", Instant.ofEpochMilli(3)).count();
        assertEquals(List.of(3L), db.last().bindings());
        preparing().table("t").where("a", Instant.ofEpochMilli(4)).delete();
        assertEquals(List.of(4L), db.last().bindings());
        preparing().table("t").insert(row("a", Instant.ofEpochMilli(5)));
        assertEquals(List.of(5L), db.last().bindings());
    }

    @Test
    void incrementAndDecrementWriteTheAmountIntoTheSql() {
        db.db().table("t").where("id", 1).increment("n");
        assertEquals(new RecordingConnection.Call("update", "update \"t\" set \"n\" = \"n\" + 1 where \"id\" = ?",
                List.of(1)), db.last());
        db.db().table("t").decrement("n", new BigDecimal("2.50"), row("u", "x"));
        assertEquals(new RecordingConnection.Call("update", "update \"t\" set \"n\" = \"n\" - 2.50, \"u\" = ?",
                List.of("x")), db.last());
        db.db().table("t").incrementEach(Map.of("a", 2L));
        assertEquals("update \"t\" set \"a\" = \"a\" + 2", db.last().sql());
        db.db().table("t").decrementEach(Map.of("a", 1.5));
        assertEquals("update \"t\" set \"a\" = \"a\" - 1.5", db.last().sql());
    }

    @Test
    void nonNumericOrNonFiniteAmountsAreRejected() {
        Builder query = db.db().table("t");

        assertThrows(IllegalArgumentException.class, () -> query.increment("n", Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> query.decrement("n", Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> query.increment("n", new java.util.concurrent.atomic.AtomicInteger(1)));
        assertTrue(db.calls.isEmpty());
    }

    @Test
    void deleteWithAndWithoutAnId() {
        db.affects(2);

        assertEquals(2, db.db().table("t").where("a", 1).delete());
        assertEquals(new RecordingConnection.Call("delete", "delete from \"t\" where \"a\" = ?", List.of(1)), db.last());
        db.db().table("users as u").delete(5);
        assertEquals(new RecordingConnection.Call("delete", "delete from \"users\" as \"u\" where \"u\".\"id\" = ?",
                List.of(5)), db.last());
    }

    @Test
    void truncateRunsOneStatementWithoutATransaction() {
        db.db().table("t").truncate();

        assertEquals(List.of(new RecordingConnection.Call("statement", "truncate table \"t\"", List.of())), db.calls);
    }

    @Test
    void truncateRunsSeveralStatementsInOneTransaction() {
        QueryGrammar twoStep = new QueryGrammar("") {
            @Override
            public Map<String, List<Object>> compileTruncate(Builder query) {
                Map<String, List<Object>> statements = new java.util.LinkedHashMap<>();
                statements.put("delete from \"t\"", List.of());
                statements.put("delete from seq where name = ?", List.of("t"));
                return statements;
            }
        };

        QueryFactory.of(db.connection, twoStep, new Processor()).table("t").truncate();

        assertEquals(List.of("begin", "delete from \"t\"", "delete from seq where name = ?", "end"),
                db.calls.stream().map(RecordingConnection.Call::sql).collect(java.util.stream.Collectors.toList()));
        assertEquals(List.of("t"), db.calls.get(2).bindings());
    }

    @Test
    void anAggregateOverHavingsKeepsLimitOffsetAndSelectBindings() {
        String sql = "select count(*) as \"aggregate\" from (select x + ? from \"t\" group by \"a\" having \"a\" > ?"
                + " limit 10 offset 5) as \"temp_table\"";
        db.returns(sql, List.of(row("aggregate", 4)));

        long count = db.db().table("t").selectRaw("x + ?", List.of(9)).groupBy("a").having("a", ">", 1)
                .limit(10).offset(5).count();

        assertEquals(4L, count);
        assertEquals(new RecordingConnection.Call("select", sql, List.of(9, 1)), db.last());
    }

    @Test
    void aGroupedAggregateKeepsItsOrdersAndOrderBindings() {
        db.db().table("t").groupBy("a").orderByRaw("f(?)", List.of(7)).limit(3).count();

        assertEquals(new RecordingConnection.Call("select",
                "select count(*) as \"aggregate\" from \"t\" group by \"a\" order by f(?)", List.of(7)), db.last());
    }

    @Test
    void numericTextInAnyFormIsANumber() {
        db.returns("select sum(\"p\") as \"aggregate\" from \"t\"", List.of(row("aggregate", "1.5E+3")));
        db.returns("select sum(\"q\") as \"aggregate\" from \"t\"", List.of(row("aggregate", "1e3")));
        db.returns("select sum(\"r\") as \"aggregate\" from \"t\"", List.of(row("aggregate", "42")));

        assertEquals(new BigDecimal("1.5E+3"), db.db().table("t").sum("p"));
        assertEquals(new BigDecimal("1e3"), db.db().table("t").sum("q"));
        assertEquals(42L, db.db().table("t").sum("r"));
    }

    @Test
    void floatAndDoubleAmountsKeepTheirShortestText() {
        db.db().table("t").increment("n", 0.1f);
        assertEquals("update \"t\" set \"n\" = \"n\" + 0.1", db.last().sql());
        db.db().table("t").increment("n", 0.1d);
        assertEquals("update \"t\" set \"n\" = \"n\" + 0.1", db.last().sql());
        db.db().table("t").increment("n", 1e20d);
        assertEquals("update \"t\" set \"n\" = \"n\" + 100000000000000000000", db.last().sql());
    }
}
