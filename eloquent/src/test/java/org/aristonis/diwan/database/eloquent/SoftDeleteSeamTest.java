package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The protected soft-delete hooks: when each runs, how often, and what a veto stops. */
class SoftDeleteSeamTest {

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
        return live(1);
    }

    private Probe live(int id) {
        return probes().query().find(id).get();
    }

    private Probe trashed() {
        return probes().query().withTrashed().find(4).get();
    }

    private ModelQuery<Probe> probes() {
        return Probe.QUERY.on(fixtures.db).withClock(ModelClock.fixed(ScopeFixtures.NOW));
    }

    private Object column(int id, String column) {
        List<Map<String, Object>> rows = fixtures.real.select(
                "select \"" + column + "\" as v from posts where id = " + id, Collections.emptyList());
        return rows.get(0).get("v");
    }

    @Test
    void seamsFireInLaravelOrder() {
        Probe softDeleted = live();
        softDeleted.delete();
        assertEquals(Arrays.asList("deleting", "softDeleted", "deleted"), softDeleted.events);

        Probe forced = live(2);
        assertTrue(forced.forceDelete());
        assertEquals(Arrays.asList("forceDeleting", "deleting", "deleted", "forceDeleted"), forced.events);

        Probe restored = trashed();
        assertTrue(restored.restore());
        assertEquals(Arrays.asList("restoring", "restored"), restored.events);
    }

    @Test
    void everySeamRunsOncePerOperation() {
        Probe model = live();

        model.delete();
        model.restore();
        model.forceDelete();

        assertEquals(1, Collections.frequency(model.events, "softDeleted"));
        assertEquals(1, Collections.frequency(model.events, "restoring"));
        assertEquals(1, Collections.frequency(model.events, "restored"));
        assertEquals(1, Collections.frequency(model.events, "forceDeleting"));
        assertEquals(1, Collections.frequency(model.events, "forceDeleted"));
        assertEquals(2, Collections.frequency(model.events, "deleting"));
        assertEquals(2, Collections.frequency(model.events, "deleted"));
    }

    @Test
    void vetoStopsTheWrite() {
        Probe forced = live();
        forced.vetoForceDeleting = true;
        Probe restoring = trashed();
        restoring.vetoRestoring = true;
        fixtures.log.clear();

        assertFalse(forced.forceDelete());
        assertFalse(restoring.restore());

        assertTrue(fixtures.log.writes().isEmpty());
        assertEquals(Collections.singletonList("forceDeleting"), forced.events);
        assertEquals(Collections.singletonList("restoring"), restoring.events);
        assertTrue(forced.exists());
        assertNotNull(column(1, "id"));
        assertNotNull(column(4, "deleted_at"));
        assertTrue(restoring.trashed());
    }

    @Test
    void vetoedForceDeleteNeverRaisesTheFlag() {
        Probe model = live();
        model.vetoForceDeleting = true;

        model.forceDelete();

        assertEquals(Collections.singletonList("forceDeleting=false"), model.flags);
        assertFalse(model.isForceDeleting());
    }

    @Test
    void flagIsRaisedAfterForceDeletingAndClearedBeforeForceDeleted() {
        Probe model = live();

        model.forceDelete();

        assertEquals(Arrays.asList("forceDeleting=false", "deleting=true", "deleted=true", "forceDeleted=false"),
                model.flags);
    }

    @Test
    void plainDeleteSeesNoFlag() {
        Probe model = live();

        model.delete();

        assertEquals(Arrays.asList("deleting=false", "deleted=false"), model.flags);
    }

    @Test
    void softDeletedRunsWithTheColumnAlreadyOriginal() {
        Probe model = live();

        model.delete();

        assertTrue(model.trashedInSoftDeleted);
        assertFalse(model.dirtyInSoftDeleted);
    }

    @Test
    void vetoedDeletingSkipsTheSoftDeleteSeam() {
        Probe model = live();
        model.vetoDeleting = true;

        assertFalse(model.delete());

        assertEquals(Collections.singletonList("deleting"), model.events);
        assertNull(column(1, "deleted_at"));
    }

    @Test
    void restoredRunsAfterTheRowIsSaved() {
        Probe model = trashed();

        model.restore();

        assertTrue(model.rowCleanInRestored);
        assertNull(column(4, "deleted_at"));
    }

    /** A soft-deleting model on the posts table that records its hooks and can veto or fail them. */
    static final class Probe extends Model {
        static final ModelQuery<Probe> QUERY = ModelQuery.of(Probe.class, Probe::new);

        final List<String> events = new ArrayList<>();
        final List<String> flags = new ArrayList<>();
        boolean vetoDeleting;
        boolean vetoForceDeleting;
        boolean vetoRestoring;
        boolean throwOnDeleting;
        boolean trashedInSoftDeleted;
        boolean dirtyInSoftDeleted;
        boolean rowCleanInRestored;

        @Override
        protected String table() {
            return "posts";
        }

        @Override
        protected boolean softDeletes() {
            return true;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }

        private void note(String event) {
            events.add(event);
            flags.add(event + "=" + isForceDeleting());
        }

        @Override
        protected boolean deleting() {
            note("deleting");
            if (throwOnDeleting) {
                throw new IllegalStateException("deleting failed");
            }
            return !vetoDeleting;
        }

        @Override
        protected void deleted() {
            note("deleted");
        }

        @Override
        protected void softDeleted() {
            events.add("softDeleted");
            trashedInSoftDeleted = trashed();
            dirtyInSoftDeleted = isDirty("deleted_at");
        }

        @Override
        protected boolean forceDeleting() {
            note("forceDeleting");
            return !vetoForceDeleting;
        }

        @Override
        protected void forceDeleted() {
            note("forceDeleted");
        }

        @Override
        protected boolean restoring() {
            events.add("restoring");
            return !vetoRestoring;
        }

        @Override
        protected void restored() {
            events.add("restored");
            rowCleanInRestored = !isDirty() && !trashed();
        }
    }
}
