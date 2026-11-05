package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelClock;
import org.aristonis.diwan.database.eloquent.ModelObserver;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Model events over Android's SQLiteDatabase, under Robolectric: order, veto, mute, rollback, retrieved. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidEventsTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:15:30Z");

    /** A soft-deleting entry whose events the tests observe. */
    public static final class Entry extends Model {
        static final ModelQuery<Entry> QUERY = ModelQuery.of(Entry.class, Entry::new);

        @Override
        protected boolean softDeletes() {
            return true;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** A row a saved listener writes. */
    public static final class Trail extends Model {
        static final ModelQuery<Trail> QUERY = ModelQuery.of(Trail.class, Trail::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** Logs {@code event:key} for every event, and can veto saving. */
    private static final class Log implements ModelObserver<Model> {
        private final List<String> events = new ArrayList<>();
        private boolean isVetoingSaving;

        private void note(String name, Model m) {
            events.add(name + ":" + m.getKey());
        }

        @Override
        public boolean saving(Model m) {
            note("saving", m);
            return !isVetoingSaving;
        }

        @Override
        public void retrieved(Model m) {
            note("retrieved", m);
        }

        @Override
        public void saved(Model m) {
            note("saved", m);
        }

        @Override
        public boolean creating(Model m) {
            note("creating", m);
            return true;
        }

        @Override
        public void created(Model m) {
            note("created", m);
        }

        @Override
        public boolean updating(Model m) {
            note("updating", m);
            return true;
        }

        @Override
        public void updated(Model m) {
            note("updated", m);
        }

        @Override
        public boolean deleting(Model m) {
            note("deleting", m);
            return true;
        }

        @Override
        public void deleted(Model m) {
            note("deleted", m);
        }

        @Override
        public void softDeleted(Model m) {
            note("softDeleted", m);
        }

        @Override
        public boolean restoring(Model m) {
            note("restoring", m);
            return true;
        }

        @Override
        public void restored(Model m) {
            note("restored", m);
        }
    }

    private SQLiteDatabase database;
    private Connection connection;
    private ModelQuery<Entry> entries;
    private Log log;

    @Before
    public void open() {
        database = SQLiteDatabase.create(null);
        connection = Connection.builder().name("android").driver(AndroidSqliteDriver.of(database)).build();
        QueryFactory db = SQLiteQuery.of(connection);
        SQLiteSchema.of(connection).create("entries", t -> {
            t.id();
            t.string("title");
            t.timestamps();
            t.softDeletes();
        });
        SQLiteSchema.of(connection).create("trails", t -> {
            t.id();
            t.string("note");
        });
        entries = Entry.QUERY.on(db).withClock(ModelClock.fixed(NOW));
        connection.statement("insert into \"entries\" (\"title\") values ('one')");
        log = new Log();
        Entry.QUERY.events().observe(log);
    }

    @After
    public void closeDatabase() {
        Entry.QUERY.events().forget();
        Trail.QUERY.events().forget();
        database.close();
    }

    private long count(String table, String where) {
        return ((Number) connection.selectOne("select count(*) as c from \"" + table + "\" where " + where)
                .get().get("c")).longValue();
    }

    @Test
    public void lifecycleEventsFireInOrder() {
        Entry fresh = entries.make(Map.of("title", "Fresh"));

        assertTrue(fresh.save());
        String id = String.valueOf(fresh.getKey());
        assertEquals(Arrays.asList("saving:null", "creating:null", "created:" + id, "saved:" + id), log.events);

        log.events.clear();
        assertTrue(fresh.delete());
        assertEquals(Arrays.asList("deleting:" + id, "softDeleted:" + id, "deleted:" + id), log.events);

        log.events.clear();
        assertTrue(fresh.restore());
        assertEquals(Arrays.asList("restoring:" + id, "saving:" + id, "updating:" + id, "updated:" + id,
                "saved:" + id, "restored:" + id), log.events);
    }

    @Test
    public void vetoedSaveWritesNothing() {
        Entry entry = entries.query().find(1L).get();
        entry.setAttribute("title", "changed");
        log.events.clear();
        log.isVetoingSaving = true;

        assertFalse(entry.save());

        assertEquals(Collections.singletonList("saving:1"), log.events);
        assertEquals(1, count("entries", "\"title\" = 'one'"));
    }

    @Test
    public void quietWriteIsSilentAndWritesTheRow() {
        Entry entry = entries.query().find(1L).get();
        log.events.clear();
        log.isVetoingSaving = true;
        entry.setAttribute("title", "quiet");

        assertTrue(entry.saveQuietly());

        assertTrue(log.events.isEmpty());
        assertEquals(1, count("entries", "\"title\" = 'quiet'"));
    }

    @Test
    public void withoutEventsMutesThisThreadAndRestoresAfterAFailure() {
        Entry entry = entries.query().find(1L).get();
        log.events.clear();

        assertThrows(IllegalStateException.class, () -> Model.withoutEvents((Runnable) () -> {
            entry.save();
            throw new IllegalStateException("failed");
        }));
        entry.save();

        assertEquals(Arrays.asList("saving:1", "saved:1"), log.events);
    }

    @Test
    public void listenerWriteRollsBackWithSaveOrFail() {
        Entry.QUERY.events().saved(m -> Trail.QUERY.on(m.getQueryFactory()).create(Map.of("note", "saved")));
        Entry.QUERY.events().saved(m -> {
            throw new IllegalStateException("late failure");
        });
        Entry fresh = entries.make(Map.of("title", "Rolled"));

        assertThrows(IllegalStateException.class, fresh::saveOrFail);

        assertEquals(0, count("entries", "\"title\" = 'Rolled'"));
        assertEquals(0, count("trails", "1 = 1"));
        assertFalse(fresh.exists());
    }

    @Test
    public void retrievedFiresPerHydratedModel() {
        connection.statement("insert into \"entries\" (\"title\") values ('two')");
        log.events.clear();

        entries.query().get();

        assertEquals(Arrays.asList("retrieved:1", "retrieved:2"), log.events);
    }
}
