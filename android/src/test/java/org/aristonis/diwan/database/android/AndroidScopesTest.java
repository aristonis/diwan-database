package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelClock;
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

/** Global scopes and soft deletes over Android's SQLiteDatabase, under Robolectric. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidScopesTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:15:30Z");
    private static final int TENANT = 7;

    /** A soft-deleting note whose scope is registered by the test. */
    public static final class Note extends Model {
        static final ModelQuery<Note> QUERY = ModelQuery.of(Note.class, Note::new);

        @Override
        protected boolean softDeletes() {
            return true;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    private SQLiteDatabase database;
    private Connection connection;
    private ModelQuery<Note> notes;

    @Before
    public void open() {
        database = SQLiteDatabase.create(null);
        connection = Connection.builder().name("android").driver(AndroidSqliteDriver.of(database)).build();
        QueryFactory db = SQLiteQuery.of(connection);
        SQLiteSchema.of(connection).create("notes", t -> {
            t.id();
            t.integer("tenant_id");
            t.string("title");
            t.timestamps();
            t.softDeletes();
        });
        Note.QUERY.addGlobalScope("tenant", builder -> builder.where("tenant_id", TENANT));
        notes = Note.QUERY.on(db).withClock(ModelClock.fixed(NOW));
        insert(1, TENANT, "one");
        insert(2, TENANT, "two");
        insert(3, 8, "other");
    }

    @After
    public void closeDatabase() {
        Note.QUERY.clearGlobalScopes();
        database.close();
    }

    private void insert(int id, int tenant, String title) {
        connection.statement("insert into \"notes\" (\"id\", \"tenant_id\", \"title\") values (" + id + ", " + tenant
                + ", '" + title + "')");
    }

    private static List<Long> keys(List<Note> models) {
        List<Long> keys = new ArrayList<>();
        for (Note note : models) {
            keys.add(((Number) note.getKey()).longValue());
        }
        Collections.sort(keys);
        return keys;
    }

    private Object deletedAt(long id) {
        return connection.selectOne("select \"deleted_at\" as v from \"notes\" where \"id\" = " + id).get().get("v");
    }

    @Test
    public void softDeleteOnAndroid() {
        assertEquals(Arrays.asList(1L, 2L), keys(notes.query().get()));
        Note one = notes.query().find(1L).get();

        assertTrue(one.delete());

        assertEquals("2026-11-05 10:15:30", deletedAt(1L));
        assertTrue(one.trashed());
        assertEquals(Collections.singletonList(2L), keys(notes.query().get()));
        assertEquals(Arrays.asList(1L, 2L), keys(notes.query().withTrashed().get()));
        assertEquals(Collections.singletonList(1L), keys(notes.query().onlyTrashed().get()));
        assertFalse(notes.query().find(1L).isPresent());

        Note trashed = notes.query().onlyTrashed().find(1L).get();
        assertTrue(trashed.restore());

        assertNull(deletedAt(1L));
        assertEquals(Arrays.asList(1L, 2L), keys(notes.query().get()));
        assertEquals(1, notes.query().where("id", 2L).delete());
        assertEquals("2026-11-05 10:15:30", deletedAt(2L));
        assertNull(deletedAt(3L));
        Note forced = notes.query().withTrashed().find(2L).get();
        assertTrue(forced.forceDelete());
        assertFalse(notes.query().withTrashed().find(2L).isPresent());
        assertTrue(notes.query().withTrashed().find(1L).isPresent());
    }

    @Test
    public void rolledBackSoftDeleteLeavesTheRowUnchanged() {
        try {
            connection.transaction(c -> {
                Note note = notes.on(c).query().find(1L).get();
                note.delete();
                throw new IllegalStateException("roll back");
            });
        } catch (IllegalStateException expected) {
            assertEquals("roll back", expected.getMessage());
        }

        assertNull(deletedAt(1L));
        assertTrue(notes.query().find(1L).isPresent());
    }
}
