package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.LockStrength;
import org.aristonis.diwan.database.query.LockWait;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.Test;

class PostgresQueryGrammarTest {

    private static Connection stub(String prefix) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getTablePrefix":
                            return prefix;
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        case "toString":
                            return "stub connection";
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private static QueryFactory db() {
        return PostgresQuery.of(stub(""));
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    @Test
    void addsPostgresOperatorsInLowerCase() {
        assertEquals("select * from \"t\" where \"a\"::text ilike ?", db().table("t").where("a", "ILIKE", "x").toSql());
        assertEquals("select * from \"t\" where \"a\" @> ?", db().table("t").where("a", "@>", "{1}").toSql());
        assertEquals("select * from \"t\" where \"a\" is distinct from ?",
                db().table("t").where("a", "IS DISTINCT FROM", 1).toSql());
        assertEquals("select * from \"t\" where \"a\" ~* ?", db().table("t").where("a", "~*", "^x").toSql());
    }

    @Test
    void operatorsWithAQuestionMarkAreNotOffered() {
        for (String operator : PostgresQuery.of(stub("")).grammar().getOperators()) {
            assertTrue(operator.indexOf('?') < 0, operator);
            assertEquals(operator.toLowerCase(java.util.Locale.ROOT), operator);
        }
        Builder query = db().table("t").where("a", "?|", "x");
        assertEquals("select * from \"t\" where \"a\" = ?", query.toSql());
        assertEquals(List.of("?|"), query.getBindings());
    }

    @Test
    void anUnknownOperatorIsNeverWrittenIntoTheSql() {
        Builder query = db().table("t").where("a", "; drop table t; --", 1);
        assertEquals("select * from \"t\" where \"a\" = ?", query.toSql());
        assertEquals(List.of("; drop table t; --"), query.getBindings());
    }

    @Test
    void likeComparesTheColumnAsText() {
        assertEquals("select * from \"t\" where \"a\"::text like ? and \"b\"::text not ilike ?",
                db().table("t").where("a", "LIKE", "x%").where("b", "not ilike", "y%").toSql());
    }

    @Test
    void whereLikeIsCaseInsensitiveByDefault() {
        assertEquals("select * from \"t\" where \"a\"::text ilike ? or \"b\"::text like ?"
                        + " and \"c\"::text not ilike ? and \"d\"::text not like ?",
                db().table("t").whereLike("a", "x%").orWhereLike("b", "y%", true)
                        .whereNotLike("c", "z%").whereNotLike("d", "w%", true).toSql());
    }

    @Test
    void injectionInNamesIsQuotedAway() {
        Builder query = db().table("t").whereLike("a\"; drop table t; --", "x");
        assertEquals("select * from \"t\" where \"a\"\"; drop table t; --\"::text ilike ?", query.toSql());
        assertEquals(List.of("x"), query.getBindings());
    }

    @Test
    void dateWheresCastOrExtract() {
        assertEquals("select * from \"t\" where \"at\"::date = ? and \"at\"::time >= ?"
                        + " and extract(day from \"at\") = ? and extract(month from \"at\") < ?"
                        + " and extract(year from \"at\") = ?",
                db().table("t").whereDate("at", LocalDate.of(2026, 1, 2)).whereTime("at", ">=", "10:00:00")
                        .whereDay("at", 5).whereMonth("at", "<", 6).whereYear("at", 2026).toSql());
    }

    @Test
    void insertGetIdReturnsTheSequenceColumn() {
        assertEquals("insert into \"t\" (\"a\") values (?) returning \"id\"",
                db().grammar().compileInsertGetId(db().table("t"), row("a", 1), "id"));
        assertEquals("insert into \"t\" (\"a\") values (?) returning \"key\"\"x\"",
                db().grammar().compileInsertGetId(db().table("t"), row("a", 1), "key\"x"));
    }

    @Test
    void updateAndDeleteWithoutLimitArePlain() {
        Builder query = db().table("t").where("a", 1);
        assertEquals("update \"t\" set \"b\" = ? where \"a\" = ?", query.getGrammar().compileUpdate(query, row("b", 2)));
        assertEquals("delete from \"t\" where \"a\" = ?", query.getGrammar().compileDelete(query));
    }

    @Test
    void updateAndDeleteWithLimitGoThroughCtid() {
        Builder query = db().table("t").where("a", 1).orderBy("id").limit(2);
        assertEquals("update \"t\" set \"b\" = ? where \"ctid\" in"
                        + " (select \"t\".\"ctid\" from \"t\" where \"a\" = ? order by \"id\" asc limit 2)",
                query.getGrammar().compileUpdate(query, row("b", 2)));
        assertEquals("delete from \"t\" where \"ctid\" in"
                        + " (select \"t\".\"ctid\" from \"t\" where \"a\" = ? order by \"id\" asc limit 2)",
                query.getGrammar().compileDelete(query));
        assertEquals("select * from \"t\" where \"a\" = ? order by \"id\" asc limit 2", query.toSql());
    }

    @Test
    void updateWithLimitUsesTheAliasAndPrefix() {
        Builder query = PostgresQuery.of(stub("app_")).table("users as u").where("u.a", 1).limit(1);
        assertEquals("update \"app_users\" as \"app_u\" set \"b\" = ? where \"ctid\" in"
                        + " (select \"app_u\".\"ctid\" from \"app_users\" as \"app_u\" where \"app_u\".\"a\" = ? limit 1)",
                query.getGrammar().compileUpdate(query, row("b", 2)));
    }

    @Test
    void truncateRestartsIdentity() {
        assertEquals(Map.of("truncate \"t\" restart identity", List.of()),
                db().grammar().compileTruncate(db().table("t")));
    }

    @Test
    void instantsAreBoundAsTheyAre() {
        Instant at = Instant.parse("2026-06-01T10:00:00.123456Z");
        assertEquals(List.of(at), db().grammar().prepareBindings(List.of(at)));
    }

    @Test
    void rawSqlWritesPostgresLiterals() {
        assertEquals("select * from \"t\" where \"a\" = true and \"b\" = false and \"c\" = 'it''s ?'"
                        + " and \"d\" is null and \"e\" = 1.50 and \"f\" = '\\x00ff'::bytea and \"g\" = '?'",
                db().table("t").where("a", true).where("b", false).where("c", "it's ?").whereNull("d")
                        .where("e", new java.math.BigDecimal("1.50")).where("f", new byte[] {0, (byte) 0xff})
                        .whereRaw("\"g\" = '?'").toRawSql());
    }

    @Test
    void rawSqlDoesNotTreatABackslashAsAnEscape() {
        assertEquals("select * from \"t\" where \"a\" = 'x\\' and \"b\" = 2",
                db().table("t").where("a", "x\\").where("b", 2).toRawSql());
    }

    @Test
    void theShortcutRefusesNull() {
        assertThrows(NullPointerException.class, () -> PostgresQuery.of(null));
    }

    @Test
    void insertOrIgnoreUsingAddsOnConflictDoNothing() {
        Builder select = db().table("table2").select("bar").where("foreign_id", 5);

        String sql = db().grammar().compileInsertOrIgnoreUsing(db().table("table1"), List.of("foo"), select.toSql());

        assertEquals("insert into \"table1\" (\"foo\") select \"bar\" from \"table2\" where \"foreign_id\" = ?"
                + " on conflict do nothing", sql);
    }

    @Test
    void updateWithAJoinPicksTheRowsByCtidAndLeavesTheBuilderAlone() {
        Builder query = db().table("users").join("orders", "users.id", "=", "orders.user_id").where("users.id", 1);
        String before = query.toSql();

        String sql = db().grammar().compileUpdate(query, row("email", "foo", "name", "bar"));

        assertEquals("update \"users\" set \"email\" = ?, \"name\" = ? where \"ctid\" in (select \"users\".\"ctid\""
                + " from \"users\" inner join \"orders\" on \"users\".\"id\" = \"orders\".\"user_id\""
                + " where \"users\".\"id\" = ?)", sql);
        assertEquals(before, query.toSql());
    }

    @Test
    void updateWithAJoinUsesTheTableAlias() {
        Builder query = db().table("users as u").join("orders as o", "u.id", "=", "o.user_id").where("o.total", ">", 9);

        assertEquals("update \"users\" as \"u\" set \"name\" = ? where \"ctid\" in (select \"u\".\"ctid\""
                + " from \"users\" as \"u\" inner join \"orders\" as \"o\" on \"u\".\"id\" = \"o\".\"user_id\""
                + " where \"o\".\"total\" > ?)", db().grammar().compileUpdate(query, row("name", "x")));
    }

    @Test
    void updateWithAJoinBindsValuesThenJoinThenWhere() {
        Builder query = db().table("users")
                .join("orders", j -> j.on("users.id", "=", "orders.user_id").where("orders.paid", 2))
                .where("users.id", 3);

        assertEquals(List.of(1, 2, 3),
                db().grammar().prepareBindingsForUpdate(query, row("name", 1)));
    }

    @Test
    void updateWithAJoinOrderAndLimitGoesThroughCtidAndBindsInMarkOrder() {
        Builder query = db().table("users")
                .join("orders", j -> j.on("users.id", "=", "orders.user_id").where("orders.paid", 2))
                .where("users.id", ">", 3)
                .orderBy("users.id")
                .limit(2);
        Map<String, Object> values = row("name", "x");

        assertEquals("update \"users\" set \"name\" = ? where \"ctid\" in (select \"users\".\"ctid\""
                + " from \"users\" inner join \"orders\" on \"users\".\"id\" = \"orders\".\"user_id\""
                + " and \"orders\".\"paid\" = ? where \"users\".\"id\" > ?"
                + " order by \"users\".\"id\" asc limit 2)", db().grammar().compileUpdate(query, values));
        assertEquals(List.of("x", 2, 3), db().grammar().prepareBindingsForUpdate(query, values));
    }

    @Test
    void deleteWithAJoinPicksTheRowsByCtid() {
        Builder query = db().table("users").join("contacts", "users.id", "=", "contacts.id")
                .where("users.email", "foo");
        String before = query.toSql();

        assertEquals("delete from \"users\" where \"ctid\" in (select \"users\".\"ctid\" from \"users\""
                + " inner join \"contacts\" on \"users\".\"id\" = \"contacts\".\"id\""
                + " where \"users\".\"email\" = ?)", db().grammar().compileDelete(query));
        assertEquals(before, query.toSql());
    }

    @Test
    void unionPartsAreWrappedInParentheses() {
        Builder query = db().table("a").select("x").where("x", 1).union(db().table("b").select("x"))
                .orderBy("x").limit(2);

        assertEquals("(select \"x\" from \"a\" where \"x\" = ?) union (select \"x\" from \"b\")"
                + " order by \"x\" asc limit 2", query.toSql());
    }

    @Test
    void eagerChunkSizes() {
        PostgresQueryGrammar grammar = new PostgresQueryGrammar("");

        assertEquals(10_000, grammar.eagerKeyChunkSize());
        assertEquals(50_000, grammar.inlineKeyChunkSize());
    }

    @Test
    void everyLockStrengthAndWaitIsWrittenLast() {
        assertEquals("select * from \"users\" order by \"id\" asc limit 2 offset 4 for update",
                db().table("users").orderBy("id").limit(2).offset(4).lockForUpdate().toSql());
        assertEquals("select * from \"users\" for share", db().table("users").sharedLock().toSql());
        assertEquals("select * from \"users\" for no key update",
                db().table("users").lock(LockStrength.NO_KEY_UPDATE).toSql());
        assertEquals("select * from \"users\" for key share nowait",
                db().table("users").lock(LockStrength.KEY_SHARE, LockWait.NOWAIT).toSql());
        assertEquals("select * from \"users\" for update skip locked",
                db().table("users").lock(LockStrength.UPDATE, LockWait.SKIP_LOCKED).toSql());
    }

    @Test
    void aUnionWithALockIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> db().table("a").lockForUpdate().union(db().table("b")).toSql());
        assertThrows(IllegalArgumentException.class,
                () -> db().table("a").union(db().table("b").sharedLock()).toSql());
    }
}
