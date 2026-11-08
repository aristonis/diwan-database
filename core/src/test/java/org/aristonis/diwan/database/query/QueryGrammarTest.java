package org.aristonis.diwan.database.query;

import org.aristonis.diwan.database.Expression;
import static org.aristonis.diwan.database.query.TestQueries.db;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QueryGrammarTest {

    private final QueryGrammar grammar = new TestQueries.TestGrammar("");

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    @Test
    void selectsColumnsFromATable() {
        assertEquals("select \"id\", \"name\" from \"users\"", db().table("users").select("id", "name").toSql());
        assertEquals("select * from \"users\"", db().table("users").toSql());
        assertEquals("select * from \"users\" as \"u\"", db().table("users as u").toSql());
    }

    @Test
    void wrapsAliasesDotsAndStars() {
        assertEquals("select \"u\".\"id\" as \"key\", \"u\".* from \"users\" as \"u\"",
                db().table("users as u").select("u.id as key", "u.*").toSql());
        assertEquals("select \"x\" as \"y\" from \"s\".\"t\"", db().table("s.t").select("x AS y").toSql());
    }

    @Test
    void addsTheTablePrefixToTablesAndQualifiedColumns() {
        assertEquals("select \"app_users\".\"id\" from \"app_users\" as \"app_u\"",
                db("app_").table("users as u").select("users.id").toSql());
        assertEquals("select * from \"s\".\"app_t\"", db("app_").table("s.t").toSql());
    }

    @Test
    void embeddedQuotesInNamesAreDoubled() {
        assertEquals("select \"a\"\"b\" from \"x\"\" ; drop table t; --\"",
                db().table("x\" ; drop table t; --").select("a\"b").toSql());
        assertEquals("select * from \"t\" where \"c\"\"\" = ? order by \"o\"\"\" asc",
                db().table("t").where("c\"", 1).orderBy("o\"").toSql());
    }

    @Test
    void distinctAndRawSelects() {
        assertEquals("select distinct \"a\", count(*) as n from \"t\"",
                db().table("t").distinct().select("a").selectRaw("count(*) as n").toSql());
        assertEquals("select 1", db().query().selectRaw("1").toSql());
    }

    @Test
    void basicWheresJoinWithTheirConjunctions() {
        Builder query = db().table("t").where("a", 1).orWhere("b", ">", 2).where("c", "like", "x%");

        assertEquals("select * from \"t\" where \"a\" = ? or \"b\" > ? and \"c\" like ?", query.toSql());
        assertEquals(List.of(1, 2, "x%"), query.getBindings());
    }

    @Test
    void nullAndNotNullWheres() {
        assertEquals("select * from \"t\" where \"a\" is null and \"b\" is not null or \"c\" is null",
                db().table("t").where("a", null).where("b", "!=", null).orWhereNull("c").toSql());
        assertEquals("select * from \"t\" where \"a\" is null and \"b\" is null",
                db().table("t").whereNull("a", "b").toSql());
    }

    @Test
    void nestedWheresAreParenthesized() {
        Builder query = db().table("t").where("a", 1).orWhere(q -> q.where("b", 2).where("c", 3));

        assertEquals("select * from \"t\" where \"a\" = ? or (\"b\" = ? and \"c\" = ?)", query.toSql());
        assertEquals(List.of(1, 2, 3), query.getBindings());
    }

    @Test
    void whereNotWrapsInNot() {
        assertEquals("select * from \"t\" where not (\"a\" = ? or \"b\" = ?)",
                db().table("t").whereNot(q -> q.where("a", 1).orWhere("b", 2)).toSql());
        assertEquals("select * from \"t\" where \"x\" = ? or not \"a\" = ?",
                db().table("t").where("x", 0).orWhereNot("a", 1).toSql());
    }

    @Test
    void columnRawLikeAndNullSafeWheres() {
        Builder query = db().table("t").whereColumn("a", "b").orWhereColumn("c", ">", "d")
                .whereRaw("e = ? or f = ?", List.of(1, 2)).whereLike("g", "x%").orWhereNotLike("h", "y%")
                .whereNullSafeEquals("i", 3);

        assertEquals("select * from \"t\" where \"a\" = \"b\" or \"c\" > \"d\" and e = ? or f = ?"
                + " and \"g\" like ? or \"h\" not like ? and \"i\" is not distinct from ?", query.toSql());
        assertEquals(List.of(1, 2, "x%", "y%", 3), query.getBindings());
    }

    @Test
    void caseSensitiveLikeIsUnsupportedByTheBaseGrammar() {
        Builder query = db().table("t").whereLike("a", "x", true);

        assertThrows(UnsupportedOperationException.class, query::toSql);
    }

    @Test
    void inWheresAndTheirEmptyForms() {
        assertEquals("select * from \"t\" where \"a\" in (?, ?, now()) and \"b\" not in (?)",
                db().table("t").whereIn("a", Arrays.asList(1, 2, Expression.raw("now()")))
                        .whereNotIn("b", List.of(3)).toSql());
        assertEquals("select * from \"t\" where 0 = 1 or 1 = 1",
                db().table("t").whereIn("a", List.of()).orWhereNotIn("b", List.of()).toSql());
    }

    @Test
    void betweenWheres() {
        Builder query = db().table("t").whereBetween("a", List.of(1, 2)).orWhereNotBetween("b", List.of(3, 4))
                .whereBetweenColumns("c", List.of("lo", "hi")).whereNotBetweenColumns("d", List.of("x", "y"))
                .whereValueBetween(5, List.of("s", "e")).orWhereValueNotBetween(6, List.of("s", "e"));

        assertEquals("select * from \"t\" where \"a\" between ? and ? or \"b\" not between ? and ?"
                + " and \"c\" between \"lo\" and \"hi\" and \"d\" not between \"x\" and \"y\""
                + " and ? between \"s\" and \"e\" or ? not between \"s\" and \"e\"", query.toSql());
        assertEquals(List.of(1, 2, 3, 4, 5, 6), query.getBindings());
    }

    @Test
    void dateBasedWheres() {
        assertEquals("select * from \"t\" where date(\"a\") = ? and time(\"a\") > ? or day(\"a\") = ?"
                + " and month(\"a\") = ? and year(\"a\") >= ?",
                db().table("t").whereDate("a", "2024-01-01").whereTime("a", ">", "10:00:00")
                        .orWhereDay("a", 1).whereMonth("a", 2).whereYear("a", ">=", 2024).toSql());
    }

    @Test
    void groupsHavingsOrdersLimitAndOffsetInLaravelsOrder() {
        Builder query = db().table("t").select("a").offset(20).limit(10).orderBy("a").orderByDesc("b")
                .havingRaw("count(*) > ?", List.of(1)).having("a", ">", 2).orHavingNull("c")
                .havingNotNull("d").havingBetween("e", List.of(3, 4)).orHavingNotBetween("f", List.of(5, 6))
                .havingNested(q -> q.having("g", 7).orHaving("h", 8))
                .groupBy("a", "b").groupByRaw("lower(c)").where("w", 0);

        assertEquals("select \"a\" from \"t\" where \"w\" = ? group by \"a\", \"b\", lower(c)"
                + " having count(*) > ? and \"a\" > ? or \"c\" is null and \"d\" is not null"
                + " and \"e\" between ? and ? or \"f\" not between ? and ? and (\"g\" = ? or \"h\" = ?)"
                + " order by \"a\" asc, \"b\" desc limit 10 offset 20", query.toSql());
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8), query.getBindings());
    }

    @Test
    void randomRawLatestAndOldestOrders() {
        assertEquals("select * from \"t\" order by RANDOM(), \"created_at\" desc, \"x\" asc, a nulls last",
                db().table("t").inRandomOrder().latest().oldest("x").orderByRaw("a nulls last").toSql());
    }

    @Test
    void forPageBeforeIdWithANullIdUsesNotNull() {
        assertEquals("select * from \"t\" where \"id\" is not null order by \"id\" desc limit 15",
                db().table("t").forPageBeforeId(15, null, "id").toSql());
    }

    @Test
    void insertsOneOrManyRows() {
        Builder query = db().table("t");

        assertEquals("insert into \"t\" (\"a\", \"b\") values (?, ?)",
                grammar.compileInsert(query, List.of(row("a", 1, "b", 2))).orElseThrow());
        assertEquals("insert into \"t\" (\"a\", \"b\") values (?, ?), (?, now())",
                grammar.compileInsert(query, List.of(row("a", 1, "b", 2),
                        row("b", Expression.raw("now()"), "a", 3))).orElseThrow());
        assertEquals("insert into \"t\" (\"a\", \"b\") values (?, ?), (?, ?)",
                grammar.compileInsert(query, List.of(row("b", 1, "a", 2), row("a", 3, "b", 4))).orElseThrow());
        assertEquals("insert into \"t\" (\"b\", \"a\") values (?, ?)",
                grammar.compileInsert(query, List.of(row("b", 1, "a", 2))).orElseThrow());
        assertEquals("insert into \"t\" default values",
                grammar.compileInsert(query, List.of(row())).orElseThrow());
        assertEquals("insert into \"t\" (\"a\") values (?)",
                grammar.compileInsertGetId(query, row("a", 1), "id"));
    }

    @Test
    void anEmptyInsertCompilesToNothing() {
        assertFalse(grammar.compileInsert(db().table("t"), List.of()).isPresent());
    }

    @Test
    void insertRowsWithDifferentKeysAreRejected() {
        Builder query = db().table("t");

        assertThrows(IllegalArgumentException.class,
                () -> grammar.compileInsert(query, List.of(row("a", 1), row("b", 2))));
        assertThrows(IllegalArgumentException.class,
                () -> grammar.compileInsert(query, List.of(row("a", 1), row("a", 1, "b", 2))));
        assertThrows(IllegalArgumentException.class, () -> grammar.compileInsert(query, List.of(row(), row())));
    }

    @Test
    void updatesAndDeletesKeepTheirWheres() {
        Builder query = db().table("t").where("id", 1);

        assertEquals("update \"t\" set \"a\" = ?, \"b\" = b + 1 where \"id\" = ?",
                grammar.compileUpdate(query, row("a", 2, "b", Expression.raw("b + 1"))));
        assertEquals("delete from \"t\" where \"id\" = ?", grammar.compileDelete(query));
        assertEquals("delete from \"t\"", grammar.compileDelete(db().table("t")));
        assertEquals(Map.of("truncate table \"t\"", List.of()), grammar.compileTruncate(query));
        assertThrows(IllegalArgumentException.class, () -> grammar.compileUpdate(query, row()));
    }

    @Test
    void truncateManyWritesOneStatementPerTableAndRefusesCascade() {
        assertEquals(List.of("truncate table \"a\"", "truncate table \"b\""),
                new ArrayList<>(grammar.compileTruncateMany(List.of("a", "b"), false).keySet()));
        assertThrows(UnsupportedOperationException.class, () -> grammar.compileTruncateMany(List.of("a"), true));
    }

    @Test
    void updateBindingsPutValuesBeforeWheresAndSkipRawValues() {
        Builder query = db().table("t").selectRaw("?", List.of("s")).where("id", 1);

        assertEquals(List.of(2, 1), grammar.prepareBindingsForUpdate(query,
                row("a", 2, "b", Expression.raw("b + 1"))));
        assertEquals(List.of(1), grammar.prepareBindingsForDelete(query));
    }

    @Test
    void existsAndAggregate() {
        Builder query = db().table("t").where("a", 1);

        assertEquals("select exists(select * from \"t\" where \"a\" = ?) as \"exists\"",
                grammar.compileExists(query));
        query.setAggregate("count", List.of("*"));
        assertEquals("select count(*) as \"aggregate\" from \"t\" where \"a\" = ?", query.toSql());
        query.distinct().setAggregate("count", List.of("a"));
        assertEquals("select count(distinct \"a\") as \"aggregate\" from \"t\" where \"a\" = ?", query.toSql());
    }

    @Test
    void anAggregateOverHavingsRunsOnASubquery() {
        Builder query = db().table("t").groupBy("a").having("a", ">", 1);
        query.setAggregate("count", List.of("*"));

        assertEquals("select count(*) as \"aggregate\" from (select * from \"t\" group by \"a\" having \"a\" > ?)"
                + " as \"temp_table\"", query.toSql());
    }

    @Test
    void rawSqlInlinesEscapedLiterals() {
        Builder query = db().table("t").where("a", "it's").where("b", 2).where("c", new BigDecimal("1.50"))
                .where("d", true).whereRaw("e = ?", java.util.Collections.singletonList(null));

        assertEquals("select * from \"t\" where \"a\" = 'it''s' and \"b\" = 2 and \"c\" = 1.50 and \"d\" = 1"
                + " and e = null", query.toRawSql());
    }

    @Test
    void rawSqlLeavesQuestionMarksInsideStringLiteralsAlone() {
        Builder query = db().table("t").whereRaw("a = '?' and b = 'x''?' and c = ?", List.of("v?"))
                .where("d", "w");

        assertEquals("select * from \"t\" where a = '?' and b = 'x''?' and c = 'v?' and \"d\" = 'w'",
                query.toRawSql());
    }

    @Test
    void rawSqlTreatsABackslashAsAnOrdinaryCharacter() {
        assertEquals("select * from t where a = 'C:\\' and b = 1",
                grammar.substituteBindingsIntoRawSql("select * from t where a = 'C:\\' and b = ?", List.of(1)));
    }

    @Test
    void rawSqlLeavesMissingBindingsAsPlaceholdersAndKeepsDoubledMarks() {
        assertEquals("a ?? 1 ?", grammar.substituteBindingsIntoRawSql("a ?? ? ?", List.of(1)));
    }

    @Test
    void rawSqlRefusesStringsWithNulBytesAndBinaryInTheBaseGrammar() {
        assertThrows(IllegalArgumentException.class,
                () -> grammar.substituteBindingsIntoRawSql("?", List.of("a\0b")));
        assertThrows(UnsupportedOperationException.class,
                () -> grammar.substituteBindingsIntoRawSql("?", List.of((Object) new byte[] {1})));
    }

    @Test
    void prepareBindingsIsAnIdentityCopyInTheBaseGrammar() {
        List<Object> bindings = Arrays.asList(1, null, "x");

        assertEquals(bindings, grammar.prepareBindings(bindings));
    }

    @Test
    void theBaseGrammarAddsNoOperatorsAndUsesRandom() {
        assertTrue(grammar.getOperators().isEmpty());
        assertEquals("RANDOM()", grammar.compileRandom(""));
    }

    @Test
    void jsonSelectorsAreRefused() {
        assertThrows(UnsupportedOperationException.class, () -> db().table("t").where("a->b", 1).toSql());
    }

    @Test
    void malformedAliasesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> db().table("t").select("a as ").toSql());
        assertThrows(IllegalArgumentException.class, () -> db().table("t").select("x as y as z").toSql());
        assertThrows(IllegalArgumentException.class, () -> db().table("t as u as v").toSql());
    }

    @Test
    void nestedGroupInsideNestedGroup() {
        Builder query = db().table("t").where("a", 1)
                .orWhere(q -> q.where("b", 2).where(r -> r.where("c", 3).orWhere("d", 4)));

        assertEquals("select * from \"t\" where \"a\" = ? or (\"b\" = ? and (\"c\" = ? or \"d\" = ?))",
                query.toSql());
        assertEquals(List.of(1, 2, 3, 4), query.getBindings());
    }

    @Test
    void nestedHavingAndHavingBetweenKeepBindingOrder() {
        Builder query = db().table("t").groupBy("g").having("a", 1)
                .havingNested(q -> q.having("b", ">", 2).orHavingBetween("c", List.of(3, 4)))
                .havingBetween("d", List.of(5, 6));

        assertEquals("select * from \"t\" group by \"g\" having \"a\" = ? and (\"b\" > ? or \"c\" between ? and ?)"
                + " and \"d\" between ? and ?", query.toSql());
        assertEquals(List.of(1, 2, 3, 4, 5, 6), query.getBindings());
    }

    @Test
    void betweenAndInMixWithRawBindingsInOrder() {
        Builder query = db().table("t").whereRaw("x = ?", List.of(1)).whereBetween("a", List.of(2, 3))
                .whereRaw("y in (?, ?)", List.of(4, 5)).whereIn("b", List.of(6, 7));

        assertEquals("select * from \"t\" where x = ? and \"a\" between ? and ? and y in (?, ?)"
                + " and \"b\" in (?, ?)", query.toSql());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), query.getBindings());
        assertEquals("select * from \"t\" where x = 1 and \"a\" between 2 and 3 and y in (4, 5)"
                + " and \"b\" in (6, 7)", query.toRawSql());
    }

    @Test
    void everyBucketLinesUpWithItsPlaceholders() {
        Builder query = db().table("t").orderByRaw("f(?)", List.of("o")).having("h", "hv")
                .where("w", "wv").selectRaw("? as s", List.of("sv"));

        assertEquals("select ? as s from \"t\" where \"w\" = ? having \"h\" = ? order by f(?)", query.toSql());
        assertEquals(List.of("sv", "wv", "hv", "o"), query.getBindings());
        assertEquals("select 'sv' as s from \"t\" where \"w\" = 'wv' having \"h\" = 'hv' order by f('o')",
                query.toRawSql());
    }

    @Test
    void forPageAfterIdKeepsARawOrderAndItsBinding() {
        Builder query = db().table("t").orderByRaw("f(?)", List.of(1)).forPageAfterId(10, 5, "id");

        assertEquals("select * from \"t\" where \"id\" > ? order by f(?), \"id\" asc limit 10", query.toSql());
        assertEquals(List.of(5, 1), query.getBindings());
    }

    @Test
    void rawSqlSkipsQuotedIdentifiers() {
        assertEquals("select \"a?b\" from \"t\" where \"x\" = 1",
                grammar.substituteBindingsIntoRawSql("select \"a?b\" from \"t\" where \"x\" = ?", List.of(1)));
        assertEquals("select \"it's\", \"q\"\"?\" from \"t\" where \"x\" = 'v'",
                grammar.substituteBindingsIntoRawSql("select \"it's\", \"q\"\"?\" from \"t\" where \"x\" = ?",
                        List.of("v")));
        assertEquals("select 'say \"hi' where a = 2",
                grammar.substituteBindingsIntoRawSql("select 'say \"hi' where a = ?", List.of(2)));
    }

    @Test
    void rawSqlOfAQuotedNameWithAQuestionMarkThroughTheBuilder() {
        assertEquals("select \"a?b\" from \"t\" where \"x\" = 1",
                db().table("t").select("a?b").where("x", 1).toRawSql());
    }

    @Test
    void eagerChunkDefaults() {
        assertEquals(900, grammar.eagerKeyChunkSize());
        assertEquals(20_000, grammar.inlineKeyChunkSize());
    }
}
