package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Recorded pivot conditions applied to a core query on the SQLite and the PostgreSQL grammar.
 * The grammars only compile SQL, so each factory runs over a stub connection that knows its
 * table prefix and needs no database server.
 */
class PivotClauseTest {

    private static final String WHERE = "select * from \"t\" where ";
    private static final String AFTER_X = "select * from \"t\" where \"x\" = ? or ";

    /** The cases shared by both grammars; each subclass supplies its factory and its LIKE form. */
    abstract class Cases {

        private final QueryFactory db;
        private final String likeSql;

        Cases(QueryFactory db, String likeSql) {
            this.db = db;
            this.likeSql = likeSql;
        }

        private Builder table() {
            return db.table("t");
        }

        private Builder afterX() {
            return db.table("t").where("x", 1);
        }

        @Test
        void basicAppliesWhere() {
            Builder query = table();

            PivotClause.basic("a", "like", "x%", false).applyTo(query);

            assertEquals(WHERE + likeSql, query.toSql());
            assertEquals(Collections.singletonList("x%"), query.getBindings());
        }

        @Test
        void twoArgumentFormHasNoOperator() {
            PivotClause clause = PivotClause.basic("a", null, 5, false);
            Builder query = table();

            clause.applyTo(query);

            assertNull(clause.operator());
            assertEquals(WHERE + "\"a\" = ?", query.toSql());
            assertEquals(Collections.singletonList(5), query.getBindings());
        }

        @Test
        void orBasicAppliesOrWhere() {
            Builder query = afterX();

            PivotClause.basic("a", ">", 5, true).applyTo(query);

            assertEquals(AFTER_X + "\"a\" > ?", query.toSql());
            assertEquals(Arrays.asList(1, 5), query.getBindings());
        }

        @Test
        void inAppliesWhereIn() {
            Builder query = table();

            PivotClause.in("a", Arrays.asList(1, 2), false, false).applyTo(query);

            assertEquals(WHERE + "\"a\" in (?, ?)", query.toSql());
            assertEquals(Arrays.asList(1, 2), query.getBindings());
        }

        @Test
        void notInAppliesWhereNotIn() {
            Builder query = table();

            PivotClause.in("a", Collections.singletonList(1), false, true).applyTo(query);

            assertEquals(WHERE + "\"a\" not in (?)", query.toSql());
            assertEquals(Collections.singletonList(1), query.getBindings());
        }

        @Test
        void orInAndOrNotIn() {
            Builder query = afterX();

            PivotClause.in("a", Arrays.asList(1, 2), true, false).applyTo(query);
            PivotClause.in("a", Collections.singletonList(3), true, true).applyTo(query);

            assertEquals(AFTER_X + "\"a\" in (?, ?) or \"a\" not in (?)", query.toSql());
            assertEquals(Arrays.asList(1, 1, 2, 3), query.getBindings());
        }

        @Test
        void emptyInIsAlwaysFalse() {
            Builder query = table();

            PivotClause.in("a", Collections.emptyList(), false, false).applyTo(query);

            assertEquals(WHERE + "0 = 1", query.toSql());
            assertEquals(Collections.emptyList(), query.getBindings());
        }

        @Test
        void betweenAppliesWhereBetween() {
            Builder query = table();

            PivotClause.between("a", Arrays.asList(1, 2), false, false).applyTo(query);

            assertEquals(WHERE + "\"a\" between ? and ?", query.toSql());
            assertEquals(Arrays.asList(1, 2), query.getBindings());
        }

        @Test
        void notBetweenAndOrForms() {
            Builder query = table();
            Builder orQuery = afterX();

            PivotClause.between("a", Arrays.asList(1, 2), false, true).applyTo(query);
            PivotClause.between("a", Arrays.asList(3, 4), true, true).applyTo(orQuery);

            assertEquals(WHERE + "\"a\" not between ? and ?", query.toSql());
            assertEquals(AFTER_X + "\"a\" not between ? and ?", orQuery.toSql());
            assertEquals(Arrays.asList(3, 4), orQuery.getBindings().subList(1, 3));
        }

        @Test
        void badBetweenListThrowsFromCore() {
            PivotClause clause = PivotClause.between("a", Collections.singletonList(1), false, false);

            assertThrows(IllegalArgumentException.class, () -> clause.applyTo(table()));
        }

        @Test
        void nullAndNotNullForms() {
            Builder isNull = table();
            Builder notNull = table();

            PivotClause.nullCheck("a", false, false).applyTo(isNull);
            PivotClause.nullCheck("a", false, true).applyTo(notNull);

            assertEquals(WHERE + "\"a\" is null", isNull.toSql());
            assertEquals(WHERE + "\"a\" is not null", notNull.toSql());
        }

        @Test
        void orNullForms() {
            Builder isNull = afterX();
            Builder notNull = afterX();

            PivotClause.nullCheck("a", true, false).applyTo(isNull);
            PivotClause.nullCheck("a", true, true).applyTo(notNull);

            assertEquals(AFTER_X + "\"a\" is null", isNull.toSql());
            assertEquals(AFTER_X + "\"a\" is not null", notNull.toSql());
        }
    }

    /** The cases on the SQLite grammar, where a LIKE compares the column as written. */
    @Nested
    class OnSQLite extends Cases {

        OnSQLite() {
            super(SQLiteQuery.of(stub("")), "\"a\" like ?");
        }
    }

    /** The cases on the PostgreSQL grammar, where a LIKE compares the column as text. */
    @Nested
    class OnPostgres extends Cases {

        OnPostgres() {
            super(PostgresQuery.of(stub("")), "\"a\"::text like ?");
        }
    }

    @Test
    void valuesListIsACopy() {
        List<Object> source = new ArrayList<>(Arrays.asList(1, 2));
        PivotClause clause = PivotClause.in("a", source, false, false);

        source.add(3);

        assertEquals(Arrays.asList(1, 2), clause.values());
    }

    @Test
    void valuesListIsUnmodifiable() {
        PivotClause clause = PivotClause.in("a", Arrays.asList(1, 2), false, false);

        assertThrows(UnsupportedOperationException.class, () -> clause.values().add(3));
    }

    @Test
    void accessorsReturnWhatWasGiven() {
        PivotClause clause = PivotClause.basic("a", "like", "x%", true);

        assertEquals(PivotClause.Kind.BASIC, clause.kind());
        assertEquals("a", clause.column());
        assertEquals("like", clause.operator());
        assertEquals(Collections.singletonList("x%"), clause.values());
        assertTrue(clause.isOr());
        assertFalse(clause.isNot());
    }

    @Test
    void nullColumnIsRejectedNamingTheColumn() {
        NullPointerException failure = assertThrows(NullPointerException.class,
                () -> PivotClause.nullCheck(null, false, false));

        assertEquals("column", failure.getMessage());
    }

    private static Connection stub(String prefix) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getTablePrefix":
                            return prefix;
                        case "getName":
                            return "test";
                        case "toString":
                            return "stub connection";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }
}
