package org.aristonis.diwan.database.query;

import org.aristonis.diwan.database.Expression;
import static org.aristonis.diwan.database.query.TestQueries.db;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class WhereIntegerInRawTest {

    @Test
    void everyExactIntegerTypeIsInlined() {
        List<Object> values = Arrays.asList((byte) 1, (short) 2, 3, 4L, BigInteger.valueOf(5),
                new BigDecimal("6.000"), "7", "-8", "0");

        Builder query = db().table("t").whereIntegerInRaw("id", values);

        assertEquals("select * from \"t\" where \"id\" in (1, 2, 3, 4, 5, 6, 7, -8, 0)", query.toSql());
    }

    @Test
    void inlinedValuesAddNoBindings() {
        Builder query = db().table("t").where("a", "x").whereIntegerInRaw("id", Arrays.asList(1, 2));

        assertEquals(Collections.singletonList("x"), query.getBindings());
    }

    @Test
    void emptyListMatchesNoRow() {
        Builder query = db().table("t").whereIntegerInRaw("id", new ArrayList<>());

        assertEquals("select * from \"t\" where 0 = 1", query.toSql());
    }

    @Test
    void longMaxAndMinValueAreExact() {
        Builder query = db().table("t").whereIntegerInRaw("id",
                Arrays.asList(Long.MAX_VALUE, Long.MIN_VALUE, String.valueOf(Long.MAX_VALUE)));

        assertEquals("select * from \"t\" where \"id\" in (9223372036854775807, -9223372036854775808,"
                + " 9223372036854775807)", query.toSql());
    }

    @Test
    void joinsWithTheAndConjunction() {
        Builder query = db().table("t").where("a", 1).whereIntegerInRaw("id", Collections.singletonList(2));

        assertEquals("select * from \"t\" where \"a\" = ? and \"id\" in (2)", query.toSql());
    }

    @Test
    void nonIntegerValuesAreRefused() {
        Object[] bad = {1.5d, 2.0d, new BigDecimal("1.5"), BigInteger.ONE.shiftLeft(64), new BigDecimal("1e30"),
            "01", " 1", "1.0", "-0", "9223372036854775808", "abc", null, Expression.raw("1 or 1 = 1")};

        for (Object value : bad) {
            List<Object> values = Collections.singletonList(value);
            assertThrows(IllegalArgumentException.class, () -> db().table("t").whereIntegerInRaw("id", values),
                    String.valueOf(value));
        }
    }

    @Test
    void messageHasTypeAndIndexOnly() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> db().table("t").whereIntegerInRaw("id", Arrays.asList(1, 2, 3, "secret-value")));

        assertEquals("value 3 of type java.lang.String is not an integer", e.getMessage());
        assertFalse(e.getMessage().contains("secret"));
    }

    @Test
    void nullValueMessageNamesNull() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> db().table("t").whereIntegerInRaw("id", Collections.singletonList(null)));

        assertEquals("value 0 of type null is not an integer", e.getMessage());
    }

    @Test
    void refusedListLeavesTheQueryUnchanged() {
        Builder query = db().table("t");

        assertThrows(IllegalArgumentException.class, () -> query.whereIntegerInRaw("id", Arrays.asList(1, "x")));

        assertTrue(query.getWheres().isEmpty());
    }

    @Test
    void nullArgumentsAreRefused() {
        assertThrows(NullPointerException.class, () -> db().table("t").whereIntegerInRaw(null, Collections.singletonList(1)));
        assertThrows(NullPointerException.class, () -> db().table("t").whereIntegerInRaw("id", null));
    }
}
