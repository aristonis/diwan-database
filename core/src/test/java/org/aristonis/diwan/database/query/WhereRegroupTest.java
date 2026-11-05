package org.aristonis.diwan.database.query;

import static org.aristonis.diwan.database.query.TestQueries.db;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code groupWheresAt} regroups only the where list: parentheses where an {@code or} needs them, bindings untouched. */
class WhereRegroupTest {

    private static final String SELECT = "select * from \"t\" where ";

    @Test
    void plainSlicesStayFlat() {
        Builder query = db().table("t").where("a", 1).where("b", 2).where("c", 3);

        query.groupWheresAt(2, true);

        assertEquals(SELECT + "\"a\" = ? and \"b\" = ? and \"c\" = ?", query.toSql());
        assertEquals(3, query.getWheres().size());
    }

    @Test
    void oldSliceWithOrIsGrouped() {
        Builder query = db().table("t").where("a", 1).orWhere("b", 2).where("c", 3);

        query.groupWheresAt(2, true);

        assertEquals(SELECT + "(\"a\" = ? or \"b\" = ?) and \"c\" = ?", query.toSql());
        assertEquals(2, query.getWheres().size());
    }

    @Test
    void newSliceWithOrIsGrouped() {
        Builder query = db().table("t").where("a", 1).orWhere("b", 2).orWhere("c", 3);

        query.groupWheresAt(1, false);

        assertEquals(SELECT + "\"a\" = ? or (\"b\" = ? or \"c\" = ?)", query.toSql());
    }

    @Test
    void newSliceJoinsWithAndWhenAsked() {
        Builder query = db().table("t").where("a", 1).orWhere("b", 2).orWhere("c", 3);

        query.groupWheresAt(1, true);

        assertEquals(SELECT + "\"a\" = ? and (\"b\" = ? or \"c\" = ?)", query.toSql());
    }

    @Test
    void bothSlicesWithOrAreGroupedSeparately() {
        Builder query = db().table("t").where("a", 1).orWhere("b", 2).where("c", 3).orWhere("d", 4);

        query.groupWheresAt(2, true);

        assertEquals(SELECT + "(\"a\" = ? or \"b\" = ?) and (\"c\" = ? or \"d\" = ?)", query.toSql());
        assertEquals(Arrays.asList(1, 2, 3, 4), query.getBindings());
    }

    @Test
    void emptyOldSlice() {
        Builder query = db().table("t").where("a", 1).orWhere("b", 2);

        query.groupWheresAt(0, true);

        assertEquals(SELECT + "(\"a\" = ? or \"b\" = ?)", query.toSql());
        assertEquals(1, query.getWheres().size());
    }

    @Test
    void aNotConjunctionLosesItsNotWhenItBecomesTheConnector() {
        Builder keepOr = db().table("t").where("x", 0).orWhereNot("a", 1).where("b", 2);
        Builder forceAnd = db().table("t").where("x", 0).orWhereNot("a", 1).where("b", 2);

        keepOr.groupWheresAt(1, false);
        forceAnd.groupWheresAt(1, true);

        assertTrue(keepOr.toSql().startsWith(SELECT + "\"x\" = ? or ("), keepOr.toSql());
        assertTrue(forceAnd.toSql().startsWith(SELECT + "\"x\" = ? and ("), forceAnd.toSql());
        assertFalse(keepOr.toSql().contains("or not ("), keepOr.toSql());
        assertEquals(Arrays.asList(0, 1, 2), keepOr.getBindings());
    }

    @Test
    void indexEqualToTheSizeChangesNothing() {
        Builder query = db().table("t").where("a", 1).orWhere("b", 2);
        String before = query.toSql();

        Builder result = query.groupWheresAt(2, true);

        assertSame(query, result);
        assertEquals(before, query.toSql());
    }

    @Test
    void indexOutOfRangeIsRefused() {
        Builder query = db().table("t").where("a", 1);

        assertThrows(IllegalArgumentException.class, () -> query.groupWheresAt(-1, true));
        assertThrows(IllegalArgumentException.class, () -> query.groupWheresAt(2, true));
        assertEquals(SELECT + "\"a\" = ?", query.toSql());
    }

    @Test
    void bindingOrderSurvivesRegrouping() {
        Builder query = db().table("t")
                .whereIn("a", Arrays.asList(1, 2, 3))
                .orWhere("b", 4)
                .whereBetween("c", Arrays.asList(5, 6))
                .where("d", 7);

        query.groupWheresAt(3, true);

        assertEquals(SELECT + "(\"a\" in (?, ?, ?) or \"b\" = ? and \"c\" between ? and ?) and \"d\" = ?",
                query.toSql());
        assertEquals(Arrays.asList(1, 2, 3, 4, 5, 6, 7), query.getBindings());
        assertEquals(SELECT + "(\"a\" in (1, 2, 3) or \"b\" = 4 and \"c\" between 5 and 6) and \"d\" = 7",
                query.toRawSql());
    }

    @Test
    void cloneAfterRegroupIsIndependent() {
        Builder query = db().table("t").where("a", 1).orWhere("b", 2).where("c", 3);
        query.groupWheresAt(2, true);
        String before = query.toSql();

        Builder copy = query.clone();
        copy.where("d", 4);

        assertEquals(before, query.toSql());
        assertEquals(SELECT + "(\"a\" = ? or \"b\" = ?) and \"c\" = ? and \"d\" = ?", copy.toSql());
        assertEquals(Arrays.asList(1, 2, 3), query.getBindings());
    }

    @Test
    void aRegroupedQueryUpdatesAndDeletesWithTheGroup() {
        RecordingConnection recording = new RecordingConnection();
        Builder update = recording.db().table("t").where("a", 1).orWhere("b", 2).where("c", 3);
        update.groupWheresAt(2, true);
        update.update(Collections.singletonMap("x", 9));
        RecordingConnection.Call updated = recording.last();
        Builder delete = recording.db().table("t").where("a", 1).orWhere("b", 2).where("c", 3);
        delete.groupWheresAt(2, true);
        delete.delete();
        RecordingConnection.Call deleted = recording.last();

        assertEquals("update \"t\" set \"x\" = ? where (\"a\" = ? or \"b\" = ?) and \"c\" = ?", updated.sql());
        assertEquals(Arrays.asList(9, 1, 2, 3), updated.bindings());
        assertEquals("delete from \"t\" where (\"a\" = ? or \"b\" = ?) and \"c\" = ?", deleted.sql());
        assertEquals(List.of(1, 2, 3), deleted.bindings());
    }
}
