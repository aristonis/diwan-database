package org.aristonis.diwan.database.query;

import static org.aristonis.diwan.database.query.TestQueries.db;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class EscapedSearchGrammarTest {

    private static final String LIKE = "select * from \"t\" where \"a\" like ? escape '!'";

    @Test
    void containsStartsAndEndsWrapTheEscapedText() {
        Builder contains = db().table("t").whereContains("a", "50%_!*?[\\");
        Builder starts = db().table("t").whereStartsWith("a", "50%_!*?[\\");
        Builder ends = db().table("t").whereEndsWith("a", "50%_!*?[\\");

        assertEquals(LIKE, contains.toSql());
        assertEquals(List.of("%50!%!_!!*?[\\%"), contains.getBindings());
        assertEquals(LIKE, starts.toSql());
        assertEquals(List.of("50!%!_!!*?[\\%"), starts.getBindings());
        assertEquals(LIKE, ends.toSql());
        assertEquals(List.of("%50!%!_!!*?[\\"), ends.getBindings());
    }

    @Test
    void notAndOrFormsCombineWithOtherWheres() {
        Builder query = db().table("t").where("x", 1).whereNotContains("a", "p").orWhereStartsWith("b", "q")
                .orWhereNotEndsWith("c", "r");

        assertEquals("select * from \"t\" where \"x\" = ? and \"a\" not like ? escape '!'"
                + " or \"b\" like ? escape '!' or \"c\" not like ? escape '!'", query.toSql());
        assertEquals(List.of(1, "%p%", "q%", "%r"), query.getBindings());
    }

    @Test
    void caseInsensitiveFlagIsTheDefault() {
        assertEquals(db().table("t").whereContains("a", "x").toSql(),
                db().table("t").whereContains("a", "x", false).toSql());
    }

    @Test
    void theBaseGrammarRefusesCaseSensitiveSearch() {
        assertThrows(UnsupportedOperationException.class,
                () -> db().table("t").whereContains("a", "x", true).toSql());
    }

    @Test
    void nullAndEmpty() {
        assertThrows(IllegalArgumentException.class, () -> db().table("t").whereContains("a", null));
        assertThrows(IllegalArgumentException.class, () -> db().table("t").orWhereNotEndsWith("a", null, false));
        assertThrows(NullPointerException.class, () -> db().table("t").whereStartsWith(null, "x"));

        Builder empty = db().table("t").whereContains("a", "");

        assertEquals(LIKE, empty.toSql());
        assertEquals(List.of("%%"), empty.getBindings());
    }

    @Test
    void whereLikeIsUnchanged() {
        Builder query = db().table("t").whereLike("a", "50%_!");

        assertEquals("select * from \"t\" where \"a\" like ?", query.toSql());
        assertEquals(List.of("50%_!"), query.getBindings());
    }
}
