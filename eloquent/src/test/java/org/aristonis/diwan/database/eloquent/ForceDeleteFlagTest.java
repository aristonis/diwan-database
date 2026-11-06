package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.eloquent.SoftDeleteSeamTest.Probe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The force-deleting flag is cleared whatever happens, so the next plain delete soft-deletes. */
class ForceDeleteFlagTest {

    private ScopeFixtures fixtures;

    @BeforeEach
    void open() throws SQLException {
        fixtures = ScopeFixtures.open();
        fixtures.seed();
    }

    @AfterEach
    void close() throws SQLException {
        ScopeFixtures.clearScopes();
        fixtures.close();
    }

    private Probe live() {
        return Probe.QUERY.on(fixtures.db).withClock(ModelClock.fixed(ScopeFixtures.NOW)).query().find(1).get();
    }

    private Object deletedAt(int id) {
        List<Map<String, Object>> rows = fixtures.real.select(
                "select deleted_at as v from posts where id = " + id, Collections.emptyList());
        assertEquals(1, rows.size(), "the row must still exist");
        return rows.get(0).get("v");
    }

    @Test
    void vetoedDeletingThenPlainDeleteSoftDeletes() {
        Probe model = live();
        model.vetoDeleting = true;

        assertFalse(model.forceDelete());
        assertFalse(model.isForceDeleting());
        model.vetoDeleting = false;
        assertTrue(model.delete());

        assertTrue(model.exists());
        assertTrue(model.trashed());
        assertEquals("2026-11-05 10:15:30", deletedAt(1));
    }

    @Test
    void throwingHookClearsTheFlag() {
        Probe model = live();
        model.throwOnDeleting = true;

        assertThrows(IllegalStateException.class, model::forceDelete);
        assertFalse(model.isForceDeleting());
        model.throwOnDeleting = false;
        assertTrue(model.delete());

        assertTrue(model.exists());
        assertNotNull(deletedAt(1));
    }

    @Test
    void failedDeleteStatementClearsTheFlag() {
        Probe model = live();
        fixtures.real.statement("create trigger block_delete before delete on posts"
                + " begin select raise(abort, 'locked'); end");

        assertThrows(QueryException.class, model::forceDelete);
        assertFalse(model.isForceDeleting());
        assertTrue(model.exists());
        assertFalse(model.events.contains("forceDeleted"));
        fixtures.real.statement("drop trigger block_delete");
        assertTrue(model.delete());

        assertNotNull(deletedAt(1));
    }

    @Test
    void forceDeleteOfAnUnsavedModelClearsTheFlag() {
        Probe model = Probe.QUERY.on(fixtures.db).newModel();
        fixtures.log.clear();

        assertFalse(model.forceDelete());

        assertFalse(model.isForceDeleting());
        assertFalse(model.events.contains("forceDeleted"));
        assertTrue(fixtures.log.writes().isEmpty());
    }

    @Test
    void successfulForceDeleteClearsTheFlagAndRemovesTheRow() {
        Probe model = live();

        assertTrue(model.forceDelete());

        assertFalse(model.isForceDeleting());
        assertFalse(model.exists());
        assertTrue(fixtures.real.select("select id from posts where id = 1", Collections.emptyList()).isEmpty());
    }

    @Test
    void aSecondModelIsNotAffectedByTheFirstOnesFlag() {
        Probe first = live();
        first.vetoDeleting = true;
        first.forceDelete();
        Probe second = Probe.QUERY.on(fixtures.db).withClock(ModelClock.fixed(ScopeFixtures.NOW)).query()
                .find(2).get();

        assertTrue(second.delete());

        assertNull(deletedAt(1));
        assertNotNull(deletedAt(2));
    }
}
