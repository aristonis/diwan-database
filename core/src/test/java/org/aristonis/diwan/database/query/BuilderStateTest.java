package org.aristonis.diwan.database.query;

import static org.aristonis.diwan.database.query.TestQueries.db;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class BuilderStateTest {

    private enum Status { ACTIVE }

    @Test
    void bindingTypesFollowLaravelsFullOrder() {
        assertEquals(Arrays.asList("SELECT", "FROM", "JOIN", "WHERE", "GROUP_BY", "HAVING", "ORDER", "UNION",
                "UNION_ORDER"), Arrays.stream(BindingType.values()).map(Enum::name).collect(
                        java.util.stream.Collectors.toList()));
    }

    @Test
    void bindingsFlattenInClauseOrderNotCallOrder() {
        Builder query = db().table("t")
                .orderByRaw("field(id, ?)", List.of(9))
                .havingRaw("sum(x) > ?", List.of(8))
                .where("a", 1)
                .groupByRaw("g + ?", List.of(7))
                .selectRaw("x + ?", List.of(6));

        assertEquals(List.of(6, 1, 7, 8, 9), query.getBindings());
    }

    @Test
    void tableAcceptsAnAlias() {
        assertEquals("t as u", db().table("t as u").getFrom());
        assertEquals("t as u", db().query().from("t", "u").getFrom());
    }

    @Test
    void twoArgumentWhereMeansEquals() {
        Builder query = db().table("t").where("a", "x");

        Where.Basic where = assertInstanceOf(Where.Basic.class, query.getWheres().get(0));
        assertEquals("=", where.operator());
        assertEquals("x", where.value());
        assertEquals("and", where.conjunction());
    }

    @Test
    void unknownOperatorBecomesTheValueWithEquals() {
        Builder query = db().table("t").where("a", "badop", null);

        Where.Basic where = assertInstanceOf(Where.Basic.class, query.getWheres().get(0));
        assertEquals("=", where.operator());
        assertEquals(List.of("badop"), query.getBindings());
    }

    @Test
    void operatorInjectionIsBoundNotWritten() {
        Builder query = db().table("t").where("a", "= 1 or 1 =", 1);

        assertEquals("select * from \"t\" where \"a\" = ?", query.toSql());
        assertEquals(List.of("= 1 or 1 ="), query.getBindings());
    }

    @Test
    void operatorCaseIsIgnoredWhenChecking() {
        Where.Basic where = assertInstanceOf(Where.Basic.class, db().table("t").where("a", "LIKE", "x%")
                .getWheres().get(0));

        assertEquals("like", where.operator());
    }

    @Test
    void nullValueBecomesIsNullAndNotEqualsBecomesIsNotNull() {
        Builder query = db().table("t").where("a", null).where("b", "=", null).where("c", "!=", null)
                .where("d", "<>", null).where("e", "<=>", null);

        assertInstanceOf(Where.Null.class, query.getWheres().get(0));
        assertInstanceOf(Where.Null.class, query.getWheres().get(1));
        assertInstanceOf(Where.NotNull.class, query.getWheres().get(2));
        assertInstanceOf(Where.NotNull.class, query.getWheres().get(3));
        assertInstanceOf(Where.Null.class, query.getWheres().get(4));
        assertTrue(query.getBindings().isEmpty());
    }

    @Test
    void nullWithAComparisonOperatorIsRejected() {
        Builder query = db().table("t");

        assertThrows(IllegalArgumentException.class, () -> query.where("a", ">", null));
        assertThrows(IllegalArgumentException.class, () -> query.having("a", "<", null));
        assertThrows(IllegalArgumentException.class, () -> query.whereDate("a", ">", null));
        assertTrue(query.getWheres().isEmpty());
    }

    @Test
    void nullSafeOperatorMakesANullSafeWhere() {
        Builder query = db().table("t").where("a", "<=>", 1);

        assertInstanceOf(Where.NullSafeEquals.class, query.getWheres().get(0));
    }

    @Test
    void whereColumnAndHavingCheckOperatorsToo() {
        Builder query = db().table("t").whereColumn("a", "; drop", "b").having("c", "x) or (1", 2);

        Where.Column column = assertInstanceOf(Where.Column.class, query.getWheres().get(0));
        assertEquals("=", column.operator());
        assertEquals("; drop", column.second());
        Having.Basic having = assertInstanceOf(Having.Basic.class, query.getHavings().get(0));
        assertEquals("=", having.operator());
        assertEquals("x) or (1", having.value());
    }

    @Test
    void mapOfConditionsIsNestedWithTheCallersConjunction() {
        Map<String, Object> conditions = new LinkedHashMap<>();
        conditions.put("a", 1);
        conditions.put("b", 2);

        Builder query = db().table("t").where("x", 0).orWhere(conditions);

        Where.Nested nested = assertInstanceOf(Where.Nested.class, query.getWheres().get(1));
        assertEquals("or", nested.conjunction());
        assertEquals("or", nested.query().getWheres().get(1).conjunction());
        assertEquals(List.of(0, 1, 2), query.getBindings());
    }

    @Test
    void emptyNestedWhereAddsNothing() {
        Builder query = db().table("t").where(q -> { });

        assertTrue(query.getWheres().isEmpty());
    }

    @Test
    void nestedBuilderStartsFromTheSameTable() {
        Builder query = db().table("t").where(q -> q.where("a", 1));

        Where.Nested nested = assertInstanceOf(Where.Nested.class, query.getWheres().get(0));
        assertEquals("t", nested.query().getFrom());
    }

    @Test
    void whereNotPrefixesTheConjunction() {
        Builder query = db().table("t").whereNot("a", 1).orWhereNot(q -> q.where("b", 2));

        assertEquals("and not", query.getWheres().get(0).conjunction());
        assertEquals("or not", query.getWheres().get(1).conjunction());
    }

    @Test
    void whereInCopiesItsValuesAndDropsExpressionsFromBindings() {
        List<Object> values = new ArrayList<>(List.of(1, 2));
        values.add(Expression.raw("3"));
        Builder query = db().table("t").whereIn("a", values);
        values.clear();

        Where.In in = assertInstanceOf(Where.In.class, query.getWheres().get(0));
        assertEquals(3, in.values().size());
        assertEquals(List.of(1, 2), query.getBindings());
        assertThrows(UnsupportedOperationException.class, () -> in.values().add(4));
    }

    @Test
    void listValuesAreRejectedOutsideWhereIn() {
        assertThrows(IllegalArgumentException.class, () -> db().table("t").where("a", List.of(1, 2)));
    }

    @Test
    void subqueryValuesAreRejectedWhereNoSubqueryIsTaken() {
        Builder other = db().table("o");

        assertThrows(IllegalArgumentException.class, () -> db().table("t").having("a", other));
        assertThrows(IllegalArgumentException.class, () -> db().table("t").whereIn("a", List.of(other)));
        assertThrows(IllegalArgumentException.class, () -> db().table("t").whereBetween("a", List.of(other, 1)));
    }

    @Test
    void betweenNeedsExactlyTwoValues() {
        assertThrows(IllegalArgumentException.class, () -> db().table("t").whereBetween("a", List.of(1)));
        assertThrows(IllegalArgumentException.class, () -> db().table("t").whereBetween("a", List.of(1, 2, 3)));
        assertThrows(IllegalArgumentException.class,
                () -> db().table("t").whereBetweenColumns("a", List.of("b")));
    }

    @Test
    void enumsAreBoundByName() {
        assertEquals(List.of("ACTIVE"), db().table("t").where("s", Status.ACTIVE).getBindings());
    }

    @Test
    void rawValuesAreNeverBound() {
        Builder query = db().table("t").where("a", Expression.raw("now()"))
                .whereNullSafeEquals("b", Expression.raw("1"));

        assertTrue(query.getBindings().isEmpty());
    }

    @Test
    void dayAndMonthArePaddedToTwoDigits() {
        Builder query = db().table("t").whereDay("d", 5).whereMonth("d", "7").whereYear("d", 2024);

        assertEquals(List.of("05", "07", 2024), query.getBindings());
    }

    @Test
    void dateWheresFormatDateValues() {
        Builder query = db().table("t").whereDate("d", java.time.LocalDate.of(2024, 3, 9))
                .whereTime("d", ">=", java.time.LocalTime.of(8, 5, 1));

        assertEquals(List.of("2024-03-09", "08:05:01"), query.getBindings());
    }

    @Test
    void invalidSortDirectionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> db().table("t").orderBy("a", "asc; drop table t"));
    }

    @Test
    void sortDirectionIsCaseInsensitive() {
        Order.Column order = assertInstanceOf(Order.Column.class,
                db().table("t").orderBy("a", "DESC").getOrders().get(0));

        assertEquals("desc", order.direction());
    }

    @Test
    void negativeLimitIsIgnoredAndNegativeOffsetBecomesZero() {
        Builder query = db().table("t").limit(5).limit(-1).offset(-3);

        assertEquals(OptionalInt.of(5), query.getLimit());
        assertEquals(OptionalInt.of(0), query.getOffset());
    }

    @Test
    void forPageSetsOffsetAndLimit() {
        Builder query = db().table("t").forPage(3, 10);

        assertEquals(OptionalInt.of(20), query.getOffset());
        assertEquals(OptionalInt.of(10), query.getLimit());
    }

    @Test
    void forPageAfterIdReplacesAnOrderOnTheSameColumn() {
        Builder query = db().table("t").orderBy("id", "desc").orderBy("name").forPageAfterId(10, 5, "id");

        assertEquals(2, query.getOrders().size());
        assertEquals("name", ((Order.Column) query.getOrders().get(0)).column());
        assertEquals(List.of(5), query.getBindings());
    }

    @Test
    void reorderDropsOrdersAndTheirBindings() {
        Builder query = db().table("t").orderByRaw("field(a, ?)", List.of(1)).reorder("b");

        assertEquals(1, query.getOrders().size());
        assertTrue(query.getBindings().isEmpty());
    }

    @Test
    void addSelectSkipsColumnsAlreadySelected() {
        Builder query = db().table("t").select("a").addSelect("a", "b");

        assertEquals(List.of("a", "b"), query.getColumns());
    }

    @Test
    void selectRejectsColumnsThatAreNeitherNamesNorExpressions() {
        assertThrows(IllegalArgumentException.class, () -> db().table("t").select(1));
    }

    @Test
    void selectResetsSelectBindings() {
        Builder query = db().table("t").selectRaw("? as x", List.of(1)).select("a");

        assertTrue(query.getBindings().isEmpty());
    }

    @Test
    void cloneSharesNoMutableStateWithTheOriginal() {
        Builder original = db().table("t").select("a").where(q -> q.where("b", 1)).orderBy("a").limit(3);

        Builder copy = original.clone();
        copy.select("z").where("c", 2).orderBy("d").limit(9);

        assertEquals(List.of("a"), original.getColumns());
        assertEquals(1, original.getWheres().size());
        assertEquals(1, ((Where.Nested) original.getWheres().get(0)).query().getWheres().size());
        assertEquals(1, original.getOrders().size());
        assertEquals(OptionalInt.of(3), original.getLimit());
        assertEquals(List.of(1), original.getBindings());
        assertEquals("select \"a\" from \"t\" where (\"b\" = ?) order by \"a\" asc limit 3", original.toSql());
    }

    @Test
    void newQuerySharesConnectionAndGrammarOnly() {
        Builder query = db().table("t").where("a", 1);

        Builder fresh = query.newQuery();

        assertFalse(fresh.getFrom() != null);
        assertEquals(query.getGrammar(), fresh.getGrammar());
        assertEquals(query.getConnection(), fresh.getConnection());
    }

    @Test
    void returnedStateIsReadOnly() {
        Builder query = db().table("t").where("a", 1);

        assertThrows(UnsupportedOperationException.class, () -> query.getWheres().clear());
        assertThrows(UnsupportedOperationException.class, () -> query.getBindings().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> query.getRawBindings().get(BindingType.WHERE).clear());
    }

    @Test
    void factoryRefusesAGrammarWithAnotherPrefix() {
        assertThrows(IllegalArgumentException.class, () -> QueryFactory.of(TestQueries.connection("app_"),
                new TestQueries.TestGrammar(""), new Processor()));
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> db().table(null));
        assertThrows(NullPointerException.class, () -> db().table("t").where((String) null, 1));
        assertThrows(NullPointerException.class, () -> db().table("t").whereIn("a", (java.util.Collection<?>) null));
        assertThrows(NullPointerException.class, () -> QueryFactory.of(null, null, null));
    }

    @Test
    void whereInstantBetweenIsAHalfOpenNestedRange() {
        java.time.Instant from = java.time.Instant.parse("2024-01-01T00:00:00Z");
        java.time.Instant to = java.time.Instant.parse("2024-02-01T00:00:00Z");

        Builder query = db().table("t").where("a", 1).orWhere("b", 2).whereInstantBetween("at", from, to);

        assertEquals("select * from \"t\" where \"a\" = ? or \"b\" = ? and (\"at\" >= ? and \"at\" < ?)",
                query.toSql());
        assertEquals(List.of(1, 2, from, to), query.getBindings());
    }

    @Test
    void whereAllAnyNoneNestEachColumn() {
        Builder query = db().table("t").whereAll(List.of("a", "b"), "like", "x%")
                .whereAny(List.of("c", "d"), 1).whereNone(List.of("e"), 2);

        assertEquals("select * from \"t\" where (\"a\" like ? and \"b\" like ?) and (\"c\" = ? or \"d\" = ?)"
                + " and not (\"e\" = ?)", query.toSql());
        assertEquals(List.of("x%", "x%", 1, 1, 2), query.getBindings());
    }

    @Test
    void identicalRawSelectsAreBothKept() {
        Builder query = db().table("t").selectRaw("a > ?", List.of(1)).selectRaw("a > ?", List.of(2));

        assertEquals("select a > ?, a > ? from \"t\"", query.toSql());
        assertEquals(List.of(1, 2), query.getBindings());
    }

    @Test
    void nestedQueryAccessorReturnsACopy() {
        Builder query = db().table("t").where(q -> q.where("a", 1)).havingNested(q -> q.having("b", 2));

        ((Where.Nested) query.getWheres().get(0)).query().where("x", 9);
        ((Having.Nested) query.getHavings().get(0)).query().having("y", 9);

        assertEquals("select * from \"t\" where (\"a\" = ?) having (\"b\" = ?)", query.toSql());
        assertEquals(List.of(1, 2), query.getBindings());
    }

    @Test
    void operatorIsWrittenInItsCanonicalForm() {
        Builder query = db().table("t").where("a", "LI\u212AE", "x").having("b", "NOT LIKE", "y");

        assertEquals("select * from \"t\" where \"a\" like ? having \"b\" not like ?", query.toSql());
    }

    @Test
    void forPageRejectsNonPositiveArgumentsAndOverflow() {
        assertThrows(IllegalArgumentException.class, () -> db().table("t").forPage(0, 10));
        assertThrows(IllegalArgumentException.class, () -> db().table("t").forPage(1, 0));
        assertThrows(ArithmeticException.class, () -> db().table("t").forPage(Integer.MAX_VALUE, 10));
    }

    @Test
    void cloneModifiedAfterCloningLeavesBothCorrect() {
        Builder original = db().table("t").where("a", 1).having("h", 2).orderByRaw("f(?)", List.of(3));

        Builder copy = original.clone().where("b", 4).orHaving("i", 5).limit(1);

        assertEquals("select * from \"t\" where \"a\" = ? having \"h\" = ? order by f(?)", original.toSql());
        assertEquals(List.of(1, 2, 3), original.getBindings());
        assertEquals("select * from \"t\" where \"a\" = ? and \"b\" = ? having \"h\" = ? or \"i\" = ?"
                + " order by f(?) limit 1", copy.toSql());
        assertEquals(List.of(1, 4, 2, 5, 3), copy.getBindings());
    }

    private static final int DEPTH = 25;

    private static java.util.function.Consumer<Builder> chain(int depth) {
        return q -> {
            q.where("c" + depth, depth).havingNested(h -> h.having("h", 0));
            if (depth > 1) {
                q.where(chain(depth - 1));
            }
        };
    }

    /** Collects every nested builder below {@code query}, failing if one is reached twice. */
    private static void collect(Builder query, java.util.Set<Builder> seen) {
        for (Where where : query.getWheres()) {
            if (where instanceof Where.Nested) {
                Builder nested = ((Where.Nested) where).builder();
                assertTrue(seen.add(nested), "a nested builder is shared");
                collect(nested, seen);
            }
        }
        for (Having having : query.getHavings()) {
            if (having instanceof Having.Nested) {
                assertTrue(seen.add(((Having.Nested) having).builder()), "a nested builder is shared");
            }
        }
    }

    @Test
    void cloningAndCompilingADeepChainIsLinear() {
        Builder query = db().table("t").where(chain(DEPTH));

        // Copying a nested group twice per level doubled the work at each level: 2^25 copies here.
        Builder copy = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(2), () -> {
                    Builder cloned = query.clone();
                    cloned.toSql();
                    return cloned;
                });

        java.util.Set<Builder> original = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.Set<Builder> copied = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        collect(query, original);
        collect(copy, copied);
        assertEquals(2 * DEPTH, copied.size());
        assertEquals(original.size(), copied.size());
        copied.retainAll(original);
        assertTrue(copied.isEmpty(), "the copy shares a nested builder with the original");
        assertEquals(query.toSql(), copy.toSql());
        assertEquals(query.getBindings(), copy.getBindings());
    }
}
