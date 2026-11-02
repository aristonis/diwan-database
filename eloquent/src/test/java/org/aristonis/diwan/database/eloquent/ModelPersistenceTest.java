package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.aristonis.diwan.database.eloquent.TestModels.Doc;
import org.aristonis.diwan.database.eloquent.TestModels.Event;
import org.aristonis.diwan.database.eloquent.TestModels.Note;
import org.aristonis.diwan.database.eloquent.TestModels.User;
import org.junit.jupiter.api.Test;

class ModelPersistenceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:15:30.123456Z");
    private static final String NOW_TEXT = "2026-11-02 10:15:30";

    private final FakeDb fake = new FakeDb();
    private final ModelQuery<User> users = User.QUERY.on(fake.db).withClock(ModelClock.fixed(NOW));

    private User existing() {
        return users.hydrate(Collections.singletonList(row("id", 1L, "name", "Ann", "email", "a@x"))).get(0);
    }

    @Test
    void saveInsertsANewModelWithTimestampsAndTakesTheId() {
        fake.nextId(7);
        User user = users.make(row("name", "Ann"));

        assertTrue(user.save());

        assertEquals("insert into \"users\" (\"name\", \"updated_at\", \"created_at\") values (?, ?, ?)",
                fake.last().sql());
        assertEquals(Arrays.asList("Ann", NOW_TEXT, NOW_TEXT), fake.last().bindings());
        assertEquals(7L, user.getKey());
        assertTrue(user.exists());
        assertTrue(user.wasRecentlyCreated());
        assertTrue(user.isClean());
        assertEquals(NOW_TEXT, user.getAttribute("created_at"));
    }

    @Test
    void saveOnACleanExistingModelRunsNoSql() {
        User user = existing();

        assertTrue(user.save());

        assertTrue(fake.calls.isEmpty());
        assertFalse(user.wasRecentlyCreated());
    }

    @Test
    void saveUpdatesOnlyDirtyColumnsByTheOriginalKey() {
        User user = existing();
        user.setAttribute("name", "Bob");
        user.setAttribute("id", 9L);

        assertTrue(user.save());

        assertEquals("update \"users\" set \"id\" = ?, \"name\" = ?, \"updated_at\" = ? where \"id\" = ?",
                fake.last().sql());
        assertEquals(Arrays.asList(9L, "Bob", NOW_TEXT, 1L), fake.last().bindings());
        assertTrue(user.wasChanged("name"));
        assertEquals("Ann", user.getPrevious().get("name"));
        assertTrue(user.isClean());
    }

    @Test
    void hooksRunInLaravelsOrder() {
        List<String> seen = new ArrayList<>();
        Hooked model = new Hooked(seen, null).bindTo(fake);

        model.setAttribute("a", 1);
        model.save();
        model.setAttribute("a", 2);
        model.save();
        model.delete();

        assertEquals(Arrays.asList("saving", "creating", "created", "saved", "saving", "updating", "updated",
                "saved", "deleting", "deleted"), seen);
    }

    @Test
    void aBeforeHookReturningFalseAbortsWithoutSql() {
        for (String hook : Arrays.asList("saving", "creating")) {
            FakeDb db = new FakeDb();
            Hooked model = new Hooked(new ArrayList<>(), hook).bindTo(db);
            model.setAttribute("a", 1);

            assertFalse(model.save(), hook);
            assertFalse(model.exists());
            assertTrue(db.calls.isEmpty(), hook);
        }
        FakeDb db = new FakeDb();
        Hooked updating = new Hooked(new ArrayList<>(), "updating").bindTo(db);
        updating.setRawAttributes(row("id", 1L, "a", 1), true);
        updating.markExisting();
        updating.setAttribute("a", 2);
        assertFalse(updating.save());
        Hooked deleting = new Hooked(new ArrayList<>(), "deleting").bindTo(db);
        deleting.setRawAttributes(row("id", 1L), true);
        deleting.markExisting();
        int before = db.calls.size();
        assertFalse(deleting.delete());
        assertTrue(deleting.exists());
        assertEquals(before, db.calls.size());
    }

    @Test
    void anExceptionFromABeforeHookLeavesTheModelUnsaved() {
        Hooked model = new Hooked(new ArrayList<>(), "throw").bindTo(fake);
        model.setAttribute("a", 1);

        assertThrows(IllegalStateException.class, model::save);

        assertFalse(model.exists());
        assertTrue(model.isDirty("a"));
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    void deleteOnAModelNotInTheDatabaseIsFalseWithoutSql() {
        assertFalse(users.newModel().delete());
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    void deleteWithoutAPrimaryKeyIsRefused() {
        Model keyless = new Hooked(new ArrayList<>(), null) {
            @Override
            protected String primaryKey() {
                return null;
            }
        }.bindTo(fake);

        IllegalStateException e = assertThrows(IllegalStateException.class, keyless::delete);
        assertEquals("No primary key defined on model.", e.getMessage());
    }

    @Test
    void deleteRemovesTheRowByItsKey() {
        User user = existing();

        assertTrue(user.delete());

        assertEquals("delete from \"users\" where \"id\" = ?", fake.last().sql());
        assertEquals(Collections.singletonList(1L), fake.last().bindings());
        assertFalse(user.exists());
    }

    @Test
    void incrementOnAModelNotInTheDatabaseIsRefused() {
        User user = users.newModel();

        assertThrows(IllegalStateException.class, () -> user.increment("votes"));
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    void incrementWritesTheColumnAndKeepsTheModelInSync() {
        User user = existing();
        user.setRawAttributes(row("id", 1L, "votes", 4), true);

        assertEquals(1, user.increment("votes", 2, row("name", "Max")));

        assertEquals("update \"users\" set \"votes\" = \"votes\" + 2, \"name\" = ?, \"updated_at\" = ? where \"id\" = ?",
                fake.last().sql());
        assertEquals(6L, user.getAttribute("votes"));
        assertEquals("Max", user.getAttribute("name"));
        assertTrue(user.isClean());
        user.decrement("votes");
        assertEquals(5L, user.getAttribute("votes"));
        user.setRawAttributes(row("id", 1L, "score", 1.5), true);
        user.increment("score", 0.25);
        assertEquals(1.75, user.getDouble("score"));
    }

    @Test
    void anAbortedIncrementPutsTheAttributesBack() {
        Hooked model = new Hooked(new ArrayList<>(), "updating").bindTo(fake);
        model.setRawAttributes(row("id", 1L, "votes", 4), true);
        model.markExisting();

        assertEquals(0, model.increment("votes", 1, row("a", 1)));

        assertEquals(4, model.getAttribute("votes"));
        assertFalse(model.hasAttribute("a"));
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    void uniqueIdsAreGeneratedOnlyForABlankKey() {
        Doc generated = Doc.QUERY.on(fake.db).make(row("title", "x"));
        generated.save();
        Doc preset = Doc.QUERY.on(fake.db).make(row("id", "kept", "title", "y"));
        preset.save();

        assertTrue(generated.getString("id").matches("[0-9a-f]{8}-[0-9a-f]{4}-7.*"));
        assertEquals("kept", preset.getKey());
        assertEquals("insert into \"docs\" (\"id\", \"title\", \"updated_at\", \"created_at\") values (?, ?, ?, ?)",
                fake.last().sql());
    }

    @Test
    void instantTimestampsAndANullUpdatedAtColumn() {
        Event event = Event.QUERY.on(fake.db).withClock(ModelClock.fixed(NOW)).make(row("name", "x"));

        event.save();

        assertEquals("insert into \"events\" (\"name\", \"id\", \"created_at\") values (?, ?, ?)", fake.last().sql());
        assertEquals(NOW, fake.last().bindings().get(2));
        assertTrue(event.getString("id").matches("[0-9a-hjkmnp-tv-z]{26}"));
        assertEquals(NOW, event.getInstant("created_at"));
    }

    @Test
    void aCustomUniqueIdGeneratorIsUsed() {
        Doc doc = new Doc() {
            @Override
            protected UniqueIds uniqueIdGenerator() {
                return new UniqueIds(() -> 1L, new Random(1));
            }
        };
        doc.setConnection(fake.db);
        doc.save();

        assertTrue(doc.getString("id").startsWith("00000000-0001-7"));
    }

    @Test
    void timestampsCanBeTurnedOffForACallback() {
        User user = users.make(row("name", "Ann"));

        boolean saved = Model.withoutTimestamps(user::save);

        assertTrue(saved);
        assertEquals("insert into \"users\" (\"name\") values (?)", fake.last().sql());
        assertTrue(user.usesTimestamps());
        assertFalse(Model.withoutTimestamps(user::usesTimestamps));
    }

    @Test
    void touchWritesOnlyTheUpdateTimestamp() {
        User user = existing();

        assertTrue(user.touch());

        assertEquals("update \"users\" set \"updated_at\" = ? where \"id\" = ?", fake.last().sql());
        assertTrue(user.touch("email_verified_at"));
        // The fixed clock gives the same updated_at again, so only the touched column is dirty.
        assertEquals("update \"users\" set \"email_verified_at\" = ? where \"id\" = ?", fake.last().sql());
        Note note = Note.QUERY.on(fake.db).newModel();
        assertFalse(note.touch());
    }

    @Test
    void updateFillsAndSavesOnlyAnExistingModel() {
        assertFalse(users.newModel().update(row("name", "x")));
        User user = existing();

        assertTrue(user.update(row("name", "Bob", "role", "admin")));

        assertEquals("update \"users\" set \"name\" = ?, \"updated_at\" = ? where \"id\" = ?", fake.last().sql());
    }

    @Test
    void refreshReloadsTheAttributesAndFreshReturnsANewModel() {
        fake.returns("select * from \"users\" where \"id\" = ? limit 1", row("id", 1L, "name", "New", "email", "n@x"));
        User user = existing();
        user.setAttribute("name", "Changed");

        assertSame(user, user.refresh());
        assertEquals("New", user.getAttribute("name"));
        assertTrue(user.isClean());
        Optional<User> fresh = user.fresh();
        assertTrue(fresh.isPresent());
        assertNotSame(user, fresh.get());
        assertEquals("New", fresh.get().getAttribute("name"));
        assertTrue(fresh.get().exists());
        assertFalse(users.newModel().fresh().isPresent());
        assertSame(fake.db, fresh.get().getQueryFactory());
    }

    @Test
    void refreshOfAVanishedRowThrows() {
        User user = existing();

        ModelNotFoundException e = assertThrows(ModelNotFoundException.class, user::refresh);
        assertEquals("No query results for model [" + User.class.getName() + "].", e.getMessage());
        assertFalse(user.fresh().isPresent());
        User fresh = users.newModel();
        assertSame(fresh, fresh.refresh());
    }

    @Test
    void replicateCopiesWithoutKeyTimestampsOrExceptedKeys() {
        User user = existing();
        user.setRawAttributes(row("id", 1L, "name", "Ann", "email", "a@x", "created_at", NOW_TEXT,
                "updated_at", NOW_TEXT), true);

        User copy = user.replicate("email");

        assertEquals(row("name", "Ann"), copy.getAttributes());
        assertFalse(copy.exists());
        assertTrue(copy.getClass() == User.class);
        assertSame(fake.db, copy.getQueryFactory());
        User whole = user.replicate();
        assertEquals(row("name", "Ann", "email", "a@x"), whole.getAttributes());
    }

    @Test
    void isComparesKeyTableAndConnection() {
        User a = existing();
        User b = users.newModel();
        b.setRawAttributes(row("id", 1), true);
        Note note = new Note();
        note.setRawAttributes(row("id", 1L), true);

        assertTrue(a.is(b));
        assertFalse(a.isNot(b));
        assertFalse(a.is(note));
        assertFalse(a.is(null));
        b.setAttribute("id", 2L);
        assertTrue(a.isNot(b));
        assertFalse(users.newModel().is(users.newModel()));
    }

    @Test
    void destroyLoadsThenDeletesEachModel() {
        fake.returns("select * from \"users\" where \"id\" in (?, ?)", row("id", 1L), row("id", 2L));

        assertEquals(2, users.destroy(1, 2));

        assertEquals(Arrays.asList("select * from \"users\" where \"id\" in (?, ?)",
                "delete from \"users\" where \"id\" = ?", "delete from \"users\" where \"id\" = ?"), fake.sql());
        assertEquals(0, users.destroy());
        assertEquals(0, users.destroy(Collections.emptyList()));
    }

    @Test
    void aModelWithoutAConnectionFailsClearly() {
        User user = new User();
        user.setAttribute("name", "x");

        IllegalStateException e = assertThrows(IllegalStateException.class, user::save);
        assertTrue(e.getMessage().contains("No connection"), e.getMessage());
    }

    @Test
    void insertGetIdKeepsANonNumericKeyAsIs() {
        fake.nextId("abc");
        User user = users.newModel();
        user.setAttribute("name", "x");

        user.save();

        assertEquals("abc", user.getKey());
        assertNull(user.getOriginal("missing"));
    }

    /** A model that records its hooks and can veto or fail one of them. */
    static class Hooked extends Model {
        private final List<String> seen;
        private final String veto;

        Hooked(List<String> seen, String veto) {
            this.seen = seen;
            this.veto = veto;
        }

        Hooked bindTo(FakeDb db) {
            setConnection(db.db);
            return this;
        }

        @Override
        protected String table() {
            return "hooked";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        private boolean before(String hook) {
            seen.add(hook);
            if ("throw".equals(veto)) {
                throw new IllegalStateException(hook);
            }
            return !hook.equals(veto);
        }

        @Override
        protected boolean saving() {
            return before("saving");
        }

        @Override
        protected boolean creating() {
            return before("creating");
        }

        @Override
        protected boolean updating() {
            return before("updating");
        }

        @Override
        protected boolean deleting() {
            return before("deleting");
        }

        @Override
        protected void saved() {
            seen.add("saved");
        }

        @Override
        protected void created() {
            seen.add("created");
        }

        @Override
        protected void updated() {
            seen.add("updated");
        }

        @Override
        protected void deleted() {
            seen.add("deleted");
        }
    }
}
