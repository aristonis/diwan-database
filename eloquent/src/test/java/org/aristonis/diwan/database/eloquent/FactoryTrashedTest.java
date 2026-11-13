package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.factory.Factory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Factory trashed: the deleted-at value on soft-delete models, and the failure on models without soft deletes. */
class FactoryTrashedTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:15:30Z");
    private static final Instant MOMENT = Instant.parse("2026-03-04T05:06:07Z");
    private static final long TOLERANCE_SECONDS = 5;

    /** A soft-deleting page with timestamps and the default deleted-at column. */
    static final class Page extends Model {
        static final ModelQuery<Page> QUERY = ModelQuery.of(Page.class, Page::new);

        @Override
        protected boolean softDeletes() {
            return true;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** A soft-deleting model with the custom deleted-at column {@code removed_on}. */
    static final class Leaflet extends Model {
        static final ModelQuery<Leaflet> QUERY = ModelQuery.of(Leaflet.class, Leaflet::new);

        @Override
        protected boolean softDeletes() {
            return true;
        }

        @Override
        protected String deletedAtColumn() {
            return "removed_on";
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** A model without soft deletes. */
    static final class Note extends Model {
        static final ModelQuery<Note> QUERY = ModelQuery.of(Note.class, Note::new);

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    private SqliteModelDb database;

    @BeforeEach
    void open() throws SQLException {
        database = SqliteModelDb.open();
        database.sql("create table pages (id integer primary key autoincrement, title text, created_at text,"
                + " updated_at text, deleted_at text)");
        database.sql("create table leaflets (id integer primary key autoincrement, title text, created_at text,"
                + " updated_at text, removed_on text)");
    }

    @AfterEach
    void close() throws SQLException {
        database.close();
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private Factory<Page> pages() {
        return Factory.of(Page.QUERY.on(database.db), () -> map("title", "p"));
    }

    private Factory<Page> pagesAtFixedTime() {
        return Factory.of(Page.QUERY.on(database.db).withClock(ModelClock.fixed(NOW)), () -> map("title", "p"));
    }

    private Instant storedMoment(String table, String column) {
        String text = String.valueOf(database.scalar("select " + column + " from " + table));
        return LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
    }

    @Test
    void trashedStoresDeletedAtAboutOneDayBeforeNow() {
        Instant before = Instant.now();

        pages().trashed().create();

        Instant expected = before.minus(Duration.ofDays(1));
        long drift = Math.abs(Duration.between(expected, storedMoment("pages", "deleted_at")).getSeconds());
        assertTrue(drift <= TOLERANCE_SECONDS, "drift in seconds: " + drift);
    }

    @Test
    void aFixedModelClockGivesAnExactValue() {
        pagesAtFixedTime().trashed().create();

        assertEquals(NOW.minus(Duration.ofDays(1)), storedMoment("pages", "deleted_at"));
    }

    @Test
    void trashedWithAMomentStoresThatMoment() {
        pagesAtFixedTime().trashed(MOMENT).create();

        assertEquals(MOMENT, storedMoment("pages", "deleted_at"));
    }

    @Test
    void aLaterStateOverridesTrashed() {
        pagesAtFixedTime().trashed().set("deleted_at", null).create();

        assertNull(database.scalar("select deleted_at from pages"));
    }

    @Test
    void trashedKeepsEarlierStates() {
        pagesAtFixedTime().set("title", "kept").trashed().create();

        assertEquals("kept", database.scalar("select title from pages"));
        assertNotNull(database.scalar("select deleted_at from pages"));
    }

    @Test
    void aCustomDeletedAtColumnIsUsed() {
        Factory.of(Leaflet.QUERY.on(database.db).withClock(ModelClock.fixed(NOW)), () -> map("title", "l"))
                .trashed().create();

        assertEquals(NOW.minus(Duration.ofDays(1)), storedMoment("leaflets", "removed_on"));
    }

    @Test
    void aCreatedTrashedModelIsStoredWithDeletedAtSet() {
        Page page = pagesAtFixedTime().trashed().create();

        assertTrue(page.exists());
        assertTrue(Page.QUERY.on(database.db).query().onlyTrashed().find(page.getKey()).isPresent());
        assertTrue(Page.QUERY.on(database.db).query().find(page.getKey()).isEmpty());
    }

    @Test
    void trashedOnAModelWithoutSoftDeletesThrowsAtMake() {
        FakeDb fake = new FakeDb();
        Factory<Note> notes = Factory.of(Note.QUERY.on(fake.db), () -> map("title", "n")).trashed();

        IllegalStateException failure = assertThrows(IllegalStateException.class, notes::make);

        assertTrue(failure.getMessage().contains("Note"), failure.getMessage());
    }

    @Test
    void trashedOnAModelWithoutSoftDeletesThrowsAtCreate() {
        FakeDb fake = new FakeDb();
        Factory<Note> notes = Factory.of(Note.QUERY.on(fake.db), () -> map("title", "n")).trashed();

        IllegalStateException failure = assertThrows(IllegalStateException.class, notes::create);

        assertTrue(failure.getMessage().contains("Note"), failure.getMessage());
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    void trashedOnAModelWithoutSoftDeletesThrowsAtRaw() {
        FakeDb fake = new FakeDb();
        Factory<Note> notes = Factory.of(Note.QUERY.on(fake.db), () -> map("title", "n")).trashed(MOMENT);

        IllegalStateException failure = assertThrows(IllegalStateException.class, notes::raw);

        assertTrue(failure.getMessage().contains("Note"), failure.getMessage());
    }

    @Test
    void trashedOnAModelWithoutSoftDeletesThrowsAtInsert() {
        FakeDb fake = new FakeDb();
        Factory<Note> notes = Factory.of(Note.QUERY.on(fake.db), () -> map("title", "n")).trashed();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> notes.insert(2));

        assertTrue(failure.getMessage().contains("Note"), failure.getMessage());
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    void buildingATrashedFactoryOnAPlainModelDoesNotThrowUntilUse() {
        FakeDb fake = new FakeDb();

        Factory<Note> notes = Factory.of(Note.QUERY.on(fake.db), () -> map("title", "n")).trashed().count(2);

        assertNotNull(notes);
        assertTrue(fake.calls.isEmpty());
    }
}
