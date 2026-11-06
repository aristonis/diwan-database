package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Writes on real SQLite: dirty state after refresh or increment, timestamps, joins and odd column names. */
class ModelWriteRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:15:30.123456Z");
    private static final String NOW_TEXT = "2026-11-02 10:15:30";

    /** An account on a table with text timestamps and open mass assignment. */
    static final class Account extends Model {
        static final ModelQuery<Account> QUERY = ModelQuery.of(Account.class, Account::new);

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** A meter with a json column. */
    static final class Meter extends Model {
        static final ModelQuery<Meter> QUERY = ModelQuery.of(Meter.class, Meter::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            return Collections.<String, Cast<?>>singletonMap("meta", Casts.json());
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** A visit with a nullable date-time column. */
    static final class Visit extends Model {
        static final ModelQuery<Visit> QUERY = ModelQuery.of(Visit.class, Visit::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            return Collections.<String, Cast<?>>singletonMap("seen_at", Casts.datetime());
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** A log line that has a created_at column and no updated_at. */
    static final class LogLine extends Model {
        static final ModelQuery<LogLine> QUERY = ModelQuery.of(LogLine.class, LogLine::new);

        @Override
        protected String updatedAtColumn() {
            return null;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** A model whose global scope hides rows with few votes; no other test registers scopes on it. */
    static final class GatedAccount extends Model {
        static final ModelQuery<GatedAccount> QUERY = ModelQuery.of(GatedAccount.class, GatedAccount::new);

        @Override
        protected String table() {
            return "accounts";
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** A post that soft-deletes, on a table with text timestamps. */
    static final class SoftPost extends Model {
        static final ModelQuery<SoftPost> QUERY = ModelQuery.of(SoftPost.class, SoftPost::new);

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
    }

    /** A ledger line whose columns are SQL keywords. */
    static final class LedgerLine extends Model {
        static final ModelQuery<LedgerLine> QUERY = ModelQuery.of(LedgerLine.class, LedgerLine::new);

        @Override
        protected String table() {
            return "ledger";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    private SqliteModelDb database;
    private ModelQuery<Account> accounts;

    @BeforeEach
    void open() throws Exception {
        database = SqliteModelDb.open();
        database.sql("create table accounts (id integer primary key autoincrement, name text, email text unique,"
                + " votes integer, created_at text, updated_at text)");
        database.sql("create table orders (id integer primary key autoincrement, account_id integer, total integer)");
        accounts = Account.QUERY.on(database.db).withClock(ModelClock.fixed(NOW));
    }

    @AfterEach
    void close() throws Exception {
        database.close();
    }

    private Account createAccount(String name, String email, int votes) {
        return accounts.create(row("name", name, "email", email, "votes", votes));
    }

    @Test
    void aNullableDateSetToNullIsSavedAsNull() {
        database.sql("create table visits (id integer primary key autoincrement, seen_at text)");
        ModelQuery<Visit> visits = Visit.QUERY.on(database.db);
        Visit visit = visits.create(row("seen_at", Instant.parse("2026-11-02T10:15:30Z")));
        assertNotNull(database.scalar("select seen_at from visits where id = 1"));

        visit.setAttribute("seen_at", null);
        boolean saved = visit.save();

        assertTrue(saved);
        assertNull(database.scalar("select seen_at from visits where id = 1"));
        assertNull(visits.findOrFail(1).getAttribute("seen_at"));
    }

    @Test
    void incrementWritesExtraColumnsThroughTheirCasts() {
        database.sql("create table meters (id integer primary key autoincrement, views integer, meta text)");
        ModelQuery<Meter> meters = Meter.QUERY.on(database.db);
        Meter meter = meters.create(row("views", 10));

        meter.increment("views", 1, row("meta", Collections.singletonMap("a", 1)));

        assertTrue(database.scalar("select meta from meters where id = 1") instanceof String);
        Meter fresh = meter.<Meter>fresh().get();
        assertEquals(11L, fresh.getLong("views"));
        assertTrue(((Map<?, ?>) fresh.getAttribute("meta")).containsKey("a"));
    }

    @Test
    void refreshSyncsTheOriginalAndLeavesThePreviousValues() {
        Account account = createAccount("pat", "pat@x", 1);
        accounts.findOrFail(account.getKey()).update(row("name", "patrick"));

        account.refresh();

        assertTrue(account.getDirty().isEmpty());
        assertEquals("patrick", account.getOriginal("name"));
        assertTrue(account.getPrevious().isEmpty());
    }

    @Test
    void incrementKeepsOtherDirtyAttributesDirty() {
        Account account = createAccount("Original", "o@x", 10);

        account.setAttribute("name", "Changed");
        account.increment("votes");

        assertTrue(account.isDirty("name"));
        assertEquals("Changed", account.getAttribute("name"));
        assertFalse(account.isDirty("votes"));
    }

    @Test
    void saveKeepsAnExplicitUpdatedAtAndCreatedAt() {
        Account account = createAccount("Ann", "a@x", 1);

        account.setAttribute("name", "Anna");
        account.setAttribute("created_at", "2019-01-01 00:00:00");
        account.setAttribute("updated_at", "2020-01-01 00:00:00");
        account.save();

        assertEquals("2019-01-01 00:00:00", database.scalar("select created_at from accounts where id = 1"));
        assertEquals("2020-01-01 00:00:00", database.scalar("select updated_at from accounts where id = 1"));
    }

    @Test
    void timestampsComeBackAfterACallbackThrows() {
        Account account = createAccount("Ann", "a@x", 1);

        assertThrows(IllegalStateException.class, () -> Model.withoutTimestamps(() -> {
            throw new IllegalStateException("boom");
        }));
        account.setAttribute("name", "Anna");
        account.setAttribute("updated_at", "2000-01-01 00:00:00");
        account.syncOriginalAttribute("updated_at");
        account.save();

        assertTrue(account.usesTimestamps());
        assertEquals(NOW_TEXT, database.scalar("select updated_at from accounts where id = 1"));
    }

    @Test
    void touchDoesNothingWhenTheModelHasNoUpdatedAtColumn() {
        database.sql("create table log_lines (id integer primary key autoincrement, text text, created_at text)");
        LogLine line = LogLine.QUERY.on(database.db).withClock(ModelClock.fixed(NOW)).create(row("text", "a"));

        boolean touched = line.touch();

        assertFalse(touched);
    }

    @Test
    void aJoinedUpdateStillWritesUpdatedAtOnTheModelsRows() {
        createAccount("Ann", "a@x", 1);
        createAccount("Bob", "b@x", 1);
        database.sql("insert into orders (account_id, total) values (1, 9), (2, 1)");

        int rows = accounts.query().join("orders", "orders.account_id", "=", "accounts.id")
                .where("orders.total", ">", 5).update(row("votes", 99));

        assertEquals(1, rows);
        assertEquals(99L, ((Number) database.scalar("select votes from accounts where id = 1")).longValue());
        assertEquals(NOW_TEXT, database.scalar("select updated_at from accounts where id = 1"));
        assertEquals(1L, ((Number) database.scalar("select votes from accounts where id = 2")).longValue());
    }

    @Test
    void anOrderedLimitedUpdateWritesOnlyThoseRows() {
        createAccount("Ann", "a@x", 1);
        createAccount("Bob", "b@x", 1);
        createAccount("Cy", "c@x", 1);

        int rows = accounts.query().orderBy("id", "desc").limit(2).update(row("votes", 7));

        assertEquals(2, rows);
        assertEquals(1L, ((Number) database.scalar("select votes from accounts where id = 1")).longValue());
        assertEquals(7L, ((Number) database.scalar("select votes from accounts where id = 3")).longValue());
    }

    @Test
    void aJoinedSoftDeleteMarksTheMatchingRows() {
        database.sql("create table posts (id integer primary key autoincrement, title text, deleted_at text,"
                + " created_at text, updated_at text)");
        database.sql("create table comments (id integer primary key autoincrement, post_id integer, body text)");
        database.sql("insert into posts (title) values ('one'), ('two')");
        database.sql("insert into comments (post_id, body) values (1, 'spam'), (2, 'fine')");
        ModelQuery<SoftPost> posts = SoftPost.QUERY.on(database.db).withClock(ModelClock.fixed(NOW));

        int rows = posts.query().join("comments", "comments.post_id", "=", "posts.id")
                .where("comments.body", "spam").delete();

        assertEquals(1, rows);
        assertEquals(NOW_TEXT, database.scalar("select deleted_at from posts where id = 1"));
        assertNull(database.scalar("select deleted_at from posts where id = 2"));
    }

    @Test
    void anInstanceIncrementIgnoresGlobalScopes() {
        database.sql("insert into accounts (name, email, votes) values ('Low', 'l@x', 1)");
        ModelQuery<GatedAccount> gated = GatedAccount.QUERY.on(database.db);
        gated.addGlobalScope("popular", builder -> builder.where("votes", ">", 100));
        try {
            GatedAccount low = gated.query().withoutGlobalScopes().findOrFail(1);

            int rows = low.increment("votes");

            assertEquals(1, rows);
            assertEquals(2L, ((Number) database.scalar("select votes from accounts where id = 1")).longValue());
        } finally {
            gated.clearGlobalScopes();
        }
    }

    @Test
    void columnsNamedLikeSqlKeywordsAreQuotedOnInsertUpdateAndRead() {
        database.sql("create table ledger (id integer primary key autoincrement, \"order\" text, \"group\" text)");
        ModelQuery<LedgerLine> ledger = LedgerLine.QUERY.on(database.db);

        LedgerLine created = ledger.create(row("order", "first", "group", "g1"));
        LedgerLine found = ledger.findOrFail(created.getKey());
        found.setAttribute("order", "second");
        found.save();

        assertEquals("first", created.getAttribute("order"));
        assertEquals("second", database.scalar("select \"order\" from ledger where id = 1"));
        assertEquals("g1", ledger.where("group", "g1").firstOrFail().getAttribute("group"));
    }
}
