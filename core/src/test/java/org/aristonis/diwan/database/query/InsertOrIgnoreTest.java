package org.aristonis.diwan.database.query;

import static org.aristonis.diwan.database.query.RecordingConnection.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The SQL and bindings of insertOrIgnore, over a grammar that writes {@code on conflict do nothing}. */
class InsertOrIgnoreTest {

    private final RecordingConnection db = new RecordingConnection().affects(2);
    private final QueryFactory factory = db.db(new UpsertStubGrammar());

    @Test
    void insertOrIgnoreReportsTheAffectedRows() {
        int added = factory.table("users").insertOrIgnore(Arrays.asList(row("email", "a", "name", "A"),
                row("email", "b", "name", "B")));

        assertEquals(2, added);
        assertEquals("affectingStatement", db.last().method());
        assertEquals("insert into \"users\" (\"email\", \"name\") values (?, ?), (?, ?) on conflict do nothing",
                db.last().sql());
        assertEquals(Arrays.asList("a", "A", "b", "B"), db.last().bindings());
    }

    @Test
    void aSingleRowKeepsItsKeyOrder() {
        factory.table("users").insertOrIgnore(row("name", "A", "email", "a"));

        assertEquals("insert into \"users\" (\"name\", \"email\") values (?, ?) on conflict do nothing",
                db.last().sql());
    }

    @Test
    void noRowsRunsNothingAndReturnsZero() {
        assertEquals(0, factory.table("users").insertOrIgnore(Collections.<java.util.Map<String, Object>>emptyList()));
        assertTrue(db.calls.isEmpty());
    }

    @Test
    void rowsWithDifferentColumnsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> factory.table("users")
                .insertOrIgnore(List.of(row("email", "a"), row("name", "b"))));
        assertTrue(db.calls.isEmpty());
    }

    @Test
    void anExpressionIsWrittenIntoTheSql() {
        factory.table("users").insertOrIgnore(row("email", "a", "seen", Expression.raw("now()")));

        assertEquals("insert into \"users\" (\"email\", \"seen\") values (?, now()) on conflict do nothing",
                db.last().sql());
        assertEquals(List.of("a"), db.last().bindings());
    }

    @Test
    void theBaseGrammarRefusesBeforeAnySql() {
        assertThrows(UnsupportedOperationException.class,
                () -> db.db().table("users").insertOrIgnore(row("email", "a")));
        assertTrue(db.calls.isEmpty());
    }
}
