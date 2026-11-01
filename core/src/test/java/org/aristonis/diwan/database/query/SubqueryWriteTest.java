package org.aristonis.diwan.database.query;

import static org.aristonis.diwan.database.query.RecordingConnection.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Writes that take a subquery or a join: insertUsing, update with a subquery value, joined update/delete. */
class SubqueryWriteTest {

    private final RecordingConnection db = new RecordingConnection();

    @Test
    void insertUsingCopiesRowsWithTheSubqueryBindings() {
        int affected = db.db().table("table1").insertUsing(Collections.singletonList("foo"),
                q -> q.select("bar").from("table2").where("foreign_id", "=", 5));
        assertEquals(1, affected);
        assertEquals("affectingStatement", db.last().method());
        assertEquals("insert into \"table1\" (\"foo\") select \"bar\" from \"table2\" where \"foreign_id\" = ?",
                db.last().sql());
        assertEquals(Collections.singletonList(5), db.last().bindings());
    }

    @Test
    void insertUsingWithoutColumnsOrWithAStarOmitsTheColumnList() {
        db.db().table("table1").insertUsing(Collections.emptyList(), db.db().table("table2"));
        assertEquals("insert into \"table1\" select * from \"table2\"", db.last().sql());
        db.db().table("table1").insertUsing(Collections.singletonList("*"), db.db().table("table2"));
        assertEquals("insert into \"table1\" select * from \"table2\"", db.last().sql());
    }

    @Test
    void insertOrIgnoreUsingIsUnsupportedByTheBaseGrammar() {
        assertThrows(UnsupportedOperationException.class, () -> db.db().table("t")
                .insertOrIgnoreUsing(Collections.singletonList("a"), db.db().table("u")));
        assertTrue(db.calls.isEmpty());
    }

    @Test
    void updateWithASubqueryValueWritesItInParenthesesAndBindsInColumnOrder() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", "n");
        values.put("credit", db.db().table("transactions").selectRaw("sum(credit)")
                .whereColumn("transactions.user_id", "=", "users.id").where("type", "=", "foo"));
        values.put("age", 3);
        db.db().table("users").where("id", "=", 1).update(values);
        assertEquals("update \"users\" set \"name\" = ?, \"credit\" = (select sum(credit) from \"transactions\" "
                + "where \"transactions\".\"user_id\" = \"users\".\"id\" and \"type\" = ?), \"age\" = ? where \"id\" = ?",
                db.last().sql());
        assertEquals(Arrays.asList("n", "foo", 3, 1), db.last().bindings());
    }

    @Test
    void updateAndDeleteWithJoinsNeedADatabaseGrammar() {
        Builder query = db.db().table("users").join("orders", "users.id", "=", "orders.user_id");
        assertThrows(UnsupportedOperationException.class,
                () -> query.update(Collections.singletonMap("email", "x")));
        assertThrows(UnsupportedOperationException.class, query::delete);
        assertTrue(db.calls.isEmpty());
        assertEquals("select * from \"users\" inner join \"orders\" on \"users\".\"id\" = \"orders\".\"user_id\"",
                query.toSql());
    }

    @Test
    void updateBindingsGoValuesThenJoinThenWhereThenTheRest() {
        QueryGrammar grammar = new TestQueries.TestGrammar("");
        Builder query = TestQueries.db().table("t")
                .join("j", j -> j.on("t.id", "=", "j.id").where("j.x", 3))
                .where("w", 4).orderByRaw("o(?)", Collections.singletonList(5)).selectRaw("s(?)", Collections.singletonList(0));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("a", 1);
        values.put("b", TestQueries.db().table("u").selectRaw("max(v)").where("k", 2));
        values.put("c", Expression.raw("c + 1"));
        List<Object> bindings = grammar.prepareBindingsForUpdate(query.getRawBindings(), values);
        assertEquals(Arrays.asList(1, 2, 3, 4, 5), bindings);
        assertEquals(Arrays.asList(3, 4, 5), grammar.prepareBindingsForDelete(query.getRawBindings()));
    }

    @Test
    void theCallersBuilderIsNotChangedByAWrite() {
        Builder query = db.db().table("users").where("id", 1);
        String sql = query.toSql();
        query.update(Collections.singletonMap("credit", db.db().table("x").selectRaw("1")));
        query.insertUsing(Collections.singletonList("a"), db.db().table("x"));
        assertEquals(sql, query.toSql());
        assertEquals(Collections.singletonList(1), query.getBindings());
    }

    @Test
    void everyWriteOnASubquerySourceIsRefusedBeforeAnythingRuns() {
        List<Consumer<Builder>> writes = Arrays.asList(
                q -> q.update(Collections.singletonMap("a", 1)),
                Builder::delete,
                q -> q.delete(7),
                Builder::truncate,
                q -> q.insert(Collections.singletonMap("a", 1)),
                q -> q.insertGetId(Collections.singletonMap("a", 1)),
                q -> q.insertUsing(Collections.singletonList("a"), db.db().table("u")),
                q -> q.insertOrIgnoreUsing(Collections.singletonList("a"), db.db().table("u")),
                q -> q.increment("a"));
        for (Consumer<Builder> write : writes) {
            Builder fromSub = db.db().query().fromSub(db.db().table("u"), "s");
            Builder fromRaw = db.db().query().fromRaw("(select 1) as s");
            IllegalStateException sub = assertThrows(IllegalStateException.class, () -> write.accept(fromSub));
            assertEquals("write needs a table name; the query reads from a subquery", sub.getMessage());
            assertThrows(IllegalStateException.class, () -> write.accept(fromRaw));
        }
        assertTrue(db.calls.isEmpty());
    }

    @Test
    void aWriteOnAQueryWithNoTableIsRefused() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> db.db().query().update(Collections.singletonMap("a", 1)));
        assertEquals("write needs a table name; the query has no table", error.getMessage());
        assertTrue(db.calls.isEmpty());
    }

    @Test
    void countOverAJoinSubKeepsTheSubqueryBindingsFirst() {
        String sql = "select count(*) as \"aggregate\" from \"users\" inner join "
                + "(select * from \"contacts\" where \"name\" = ?) as \"sub\" on \"users\".\"id\" = \"sub\".\"id\" "
                + "where \"users\".\"active\" = ?";
        db.returns(sql, Collections.singletonList(row("aggregate", 2L)));
        Builder query = db.db().table("users")
                .joinSub(db.db().table("contacts").where("name", "foo"), "sub", "users.id", "=", "sub.id")
                .where("users.active", 1);
        assertEquals(2L, query.count());
        assertEquals(sql, db.last().sql());
        assertEquals(Arrays.asList("foo", 1), db.last().bindings());
    }
}
