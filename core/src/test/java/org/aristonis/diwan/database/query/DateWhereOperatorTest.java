package org.aristonis.diwan.database.query;

import static org.aristonis.diwan.database.query.TestQueries.db;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** An unknown operator on a date-based where is treated as the value, so it never reaches the SQL. */
class DateWhereOperatorTest {

    private static final String INJECTION = "? OR 1=1";

    @Test
    void anUnknownOperatorOnWhereDateIsBoundAsTheValue() {
        Builder query = db().table("posts").whereDate("created_at", INJECTION, "2018-01-02");

        Where.DateBased where = assertInstanceOf(Where.DateBased.class, query.getWheres().get(0));
        assertEquals("=", where.operator());
        assertEquals(INJECTION, where.value());
        assertEquals("select * from \"posts\" where date(\"created_at\") = ?", query.toSql());
        assertEquals(List.of(INJECTION), query.getBindings());
    }

    @Test
    void anUnknownOperatorOnOrWhereDateKeepsTheOrConjunction() {
        Builder query = db().table("posts").where("id", 1).orWhereDate("created_at", INJECTION, "2018-01-02");

        Where.DateBased where = assertInstanceOf(Where.DateBased.class, query.getWheres().get(1));
        assertEquals("or", where.conjunction());
        assertEquals("select * from \"posts\" where \"id\" = ? or date(\"created_at\") = ?", query.toSql());
        assertEquals(List.of(1, INJECTION), query.getBindings());
    }

    @Test
    void anUnknownOperatorOnWhereTimeIsBoundAsTheValue() {
        Builder query = db().table("posts").whereTime("created_at", INJECTION, "10:00:00");

        assertEquals("select * from \"posts\" where time(\"created_at\") = ?", query.toSql());
        assertEquals(List.of(INJECTION), query.getBindings());
    }

    @Test
    void anUnknownOperatorOnOrWhereTimeIsBoundAsTheValue() {
        Builder query = db().table("posts").where("id", 1).orWhereTime("created_at", INJECTION, "10:00:00");

        assertEquals("select * from \"posts\" where \"id\" = ? or time(\"created_at\") = ?", query.toSql());
        assertEquals(List.of(1, INJECTION), query.getBindings());
    }

    @Test
    void anUnknownOperatorOnWhereYearIsBoundAsTheValue() {
        Builder query = db().table("posts").whereYear("created_at", INJECTION, 2018);

        assertEquals("select * from \"posts\" where year(\"created_at\") = ?", query.toSql());
        assertEquals(List.of(INJECTION), query.getBindings());
    }

    @Test
    void anUnknownOperatorOnOrWhereYearIsBoundAsTheValue() {
        Builder query = db().table("posts").where("id", 1).orWhereYear("created_at", INJECTION, 2018);

        assertEquals("select * from \"posts\" where \"id\" = ? or year(\"created_at\") = ?", query.toSql());
        assertEquals(List.of(1, INJECTION), query.getBindings());
    }

    // Laravel turns the text into day or month 00; Diwan refuses a day or month that is not a whole number.
    @Test
    void anUnknownOperatorOnADayOrMonthWhereIsRefusedBeforeAnySql() {
        assertThrows(IllegalArgumentException.class, () -> db().table("posts").whereDay("created_at", INJECTION, 2));
        assertThrows(IllegalArgumentException.class,
                () -> db().table("posts").orWhereDay("created_at", INJECTION, 2));
        assertThrows(IllegalArgumentException.class,
                () -> db().table("posts").whereMonth("created_at", INJECTION, 1));
        assertThrows(IllegalArgumentException.class,
                () -> db().table("posts").orWhereMonth("created_at", INJECTION, 1));
    }
}
