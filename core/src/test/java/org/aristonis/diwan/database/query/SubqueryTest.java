package org.aristonis.diwan.database.query;

import org.aristonis.diwan.database.Expression;
import static org.aristonis.diwan.database.query.TestQueries.db;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** Subquery golden SQL per slot, ported from Laravel's DatabaseQueryBuilderTest. */
class SubqueryTest {

    @Test
    void selectSubFromAConsumerABuilderOrRawText() {
        Builder query = db().table("one").select("foo", "bar")
                .selectSub(q -> q.from("two").select("baz").where("subkey", "=", "subval"), "sub")
                .where("key", "=", "val");
        assertEquals("select \"foo\", \"bar\", (select \"baz\" from \"two\" where \"subkey\" = ?) as \"sub\" "
                + "from \"one\" where \"key\" = ?", query.toSql());
        assertEquals(Arrays.asList("subval", "val"), query.getBindings());
        assertEquals("select (select 1) as \"one\" from \"t\"", db().table("t").selectSub("select 1", "one").toSql());
        assertEquals("select \"a\", (select count(*) from \"c\") as \"n\" from \"t\"",
                db().table("t").select("a").addSelect("n", db().table("c").selectRaw("count(*)")).toSql());
    }

    @Test
    void fromSubFromRawAndFromBuilder() {
        Builder query = db().query().fromSub(q -> q.selectRaw("max(last_seen_at) as last_seen_at")
                .from("user_sessions").where("foo", "=", "1"), "sessions").where("bar", "<", "10");
        assertEquals("select * from (select max(last_seen_at) as last_seen_at from \"user_sessions\" "
                + "where \"foo\" = ?) as \"sessions\" where \"bar\" < ?", query.toSql());
        assertEquals(Arrays.asList("1", "10"), query.getBindings());
        assertEquals("select * from (select max(x) from \"s\") as \"sessions\"",
                db().query().fromRaw("(select max(x) from \"s\") as \"sessions\"").toSql());
        Builder raw = db().query().fromRaw("generate_series(?, ?) as g", Arrays.asList(1, 3));
        assertEquals(Arrays.asList(1, 3), raw.getRawBindings().get(BindingType.FROM));
        assertEquals("select * from (select * from \"t\") as \"x\"",
                db().query().from(db().table("t"), "x").toSql());
        assertEquals("select * from (select * from \"p_t\") as \"p_x\"",
                db("p_").query().from(q -> q.from("t"), "x").toSql());
    }

    @Test
    void fromAfterFromSubDropsTheSubAndItsBindings() {
        Builder query = db().query().fromSub(db().table("t").where("a", 1), "x").from("users");
        assertEquals("select * from \"users\"", query.toSql());
        assertTrue(query.getBindings().isEmpty());
        assertEquals("users", query.getFrom());
    }

    @Test
    void fromSubLeavesGetFromNullAndExposesTheExpression() {
        Builder query = db().query().fromSub("select 1", "x");
        assertEquals(null, query.getFrom());
        assertEquals("(select 1) as \"x\"", query.getFromExpression().map(Expression::sql).orElse(""));
    }

    @Test
    void whereColumnComparedWithASubquery() {
        Builder query = db().table("users").where("email", "=", "foo")
                .orWhere("id", "=", q -> q.selectRaw("max(id)").from("users").where("email", "=", "bar"));
        assertEquals("select * from \"users\" where \"email\" = ? or \"id\" = (select max(id) from \"users\" "
                + "where \"email\" = ?)", query.toSql());
        assertEquals(Arrays.asList("foo", "bar"), query.getBindings());
        assertEquals("select * from \"t\" where \"a\" > (select \"b\" from \"u\")",
                db().table("t").where("a", ">", db().table("u").select("b")).toSql());
        assertEquals("select * from \"t\" where \"a\" = (select \"b\" from \"u\")",
                db().table("t").where("a", db().table("u").select("b")).toSql());
    }

    @Test
    void whereWithANullConsumerIsStillIsNull() {
        assertEquals("select * from \"t\" where \"a\" is null",
                db().table("t").where("a", "=", (Consumer<Builder>) null).toSql());
    }

    @Test
    void subqueryComparedWithAValue() {
        Builder query = db().table("users")
                .where(db().table("orders").selectRaw("count(*)").whereColumn("orders.user_id", "users.id"), ">", 5)
                .orWhere(q -> q.from("x").selectRaw("max(y)").where("z", 1), "<", 9);
        assertEquals("select * from \"users\" where (select count(*) from \"orders\" where \"orders\".\"user_id\" = "
                + "\"users\".\"id\") > ? or (select max(y) from \"x\" where \"z\" = ?) < ?", query.toSql());
        assertEquals(Arrays.asList(5, 1, 9), query.getBindings());
        assertEquals("select * from \"t\" where (select \"a\" from \"u\") is null",
                db().table("t").where(db().table("u").select("a"), "=", null).toSql());
    }

    @Test
    void whereInAndNotInWithASubquery() {
        Builder query = db().table("users")
                .whereIn("id", q -> q.select("id").from("users").where("age", ">", 25).limit(3));
        assertEquals("select * from \"users\" where \"id\" in (select \"id\" from \"users\" where \"age\" > ? limit 3)",
                query.toSql());
        assertEquals(Collections.singletonList(25), query.getBindings());
        assertEquals("select * from \"users\" where \"id\" not in (select \"id\" from \"x\") "
                + "or \"id\" in (select \"id\" from \"y\") or \"id\" not in (select \"id\" from \"z\")",
                db().table("users").whereNotIn("id", db().table("x").select("id"))
                        .orWhereIn("id", q -> q.from("y").select("id"))
                        .orWhereNotIn("id", db().table("z").select("id")).toSql());
    }

    @Test
    void whereExistsFamily() {
        UnaryOperator<Builder> products = q -> q.selectRaw("*").from("products")
                .whereColumn("products.id", "=", "orders.id");
        assertEquals("select * from \"orders\" where exists (select * from \"products\" where \"products\".\"id\" = "
                + "\"orders\".\"id\")", db().table("orders").whereExists(q -> products.apply(q)).toSql());
        assertEquals("select * from \"orders\" where not exists (select * from \"products\" where \"products\".\"id\" = "
                + "\"orders\".\"id\")", db().table("orders").whereNotExists(products.apply(db().query())).toSql());
        Builder query = db().table("orders").where("id", "=", 1)
                .orWhereExists(db().table("p").where("a", 2)).orWhereNotExists(q -> q.from("n").where("b", 3))
                .whereExists(db().table("e"));
        assertEquals("select * from \"orders\" where \"id\" = ? or exists (select * from \"p\" where \"a\" = ?) "
                + "or not exists (select * from \"n\" where \"b\" = ?) and exists (select * from \"e\")", query.toSql());
        assertEquals(Arrays.asList(1, 2, 3), query.getBindings());
    }

    @Test
    void whereRowValues() {
        Builder query = db().table("orders").whereRowValues(Arrays.asList("last_update", "order_number"), "<",
                Arrays.asList(1, 2)).orWhereRowValues(Arrays.asList("a", "b"), "=", Arrays.asList(3, Expression.raw("4")));
        assertEquals("select * from \"orders\" where (\"last_update\", \"order_number\") < (?, ?) "
                + "or (\"a\", \"b\") = (?, 4)", query.toSql());
        assertEquals(Arrays.asList(1, 2, 3), query.getBindings());
    }

    @Test
    void whereRowValuesRefusesMismatchedCountsAndUnknownOperators() {
        Builder query = db().table("t");
        assertThrows(IllegalArgumentException.class,
                () -> query.whereRowValues(Arrays.asList("a", "b"), "=", Collections.singletonList(1)));
        assertThrows(IllegalArgumentException.class,
                () -> query.whereRowValues(Collections.singletonList("a"), "= 1; drop table t; --",
                        Collections.singletonList(1)));
        assertTrue(query.getWheres().isEmpty());
        assertTrue(query.getBindings().isEmpty());
    }

    @Test
    void orderByASubquery() {
        Consumer<Builder> logins = q -> q.select("created_at").from("logins")
                .whereColumn("user_id", "users.id").limit(1);
        assertEquals("select * from \"users\" order by (select \"created_at\" from \"logins\" where \"user_id\" = "
                + "\"users\".\"id\" limit 1) asc", db().table("users").orderBy(logins).toSql());
        Builder query = db().table("users").orderBy(db().table("x").select("y").where("z", 1), "DESC");
        assertEquals("select * from \"users\" order by (select \"y\" from \"x\" where \"z\" = ?) desc", query.toSql());
        assertEquals(Collections.singletonList(1), query.getRawBindings().get(BindingType.ORDER));
    }

    @Test
    void mutatingASubAfterAttachChangesNothing() {
        Builder sub = db().table("s").select("id").where("a", 1);
        Builder outer = db().table("t")
                .selectSub(sub, "c")
                .joinSub(sub, "j", "t.id", "=", "j.id")
                .whereIn("id", sub)
                .where("x", "=", sub)
                .whereExists(sub)
                .orderBy(sub, "asc")
                .union(sub);
        String sql = outer.toSql();
        List<Object> bindings = outer.getBindings();
        sub.where("b", 2).orderBy("id").limit(4);
        assertEquals(sql, outer.toSql());
        assertEquals(bindings, outer.getBindings());
        Builder from = db().query().fromSub(sub, "f");
        String fromSql = from.toSql();
        sub.where("c", 3);
        assertEquals(fromSql, from.toSql());
    }

    @Test
    void cloneOfAJoinedUnionedQueryIsIndependent() {
        Builder original = db().table("a").join("b", "a.id", "=", "b.id").whereExists(db().table("e").where("x", 1))
                .union(db().table("c").where("y", 2)).orderBy("id").limit(5);
        String sql = original.toSql();
        List<Object> bindings = original.getBindings();
        Builder copy = original.clone();
        copy.leftJoin("d", "a.id", "=", "d.id").union(db().table("u")).orderBy("z").limit(9).offset(2)
                .where("w", 3);
        assertEquals(sql, original.toSql());
        assertEquals(bindings, original.getBindings());
        assertFalse(copy.toSql().equals(sql));
    }

    @Test
    void stringValuesAreStillPlainValues() {
        assertEquals(Collections.singletonList("x"), db().table("t").where("a", "=", (Object) "x").getBindings());
    }
}
