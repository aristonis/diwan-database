package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.jdbc.JdbcConnectionProvider;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Models against a real in-memory SQLite database, tables built with the schema builder. */
class SQLiteModelTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:15:30.123456Z");

    /** A text-timestamp model on a table built with timestamps(). */
    static final class Account extends Model {
        static final ModelQuery<Account> QUERY = ModelQuery.of(Account.class, Account::new);

        @Override
        protected List<String> fillable() {
            return Arrays.asList("name", "email", "votes");
        }
    }

    /** A UUID-keyed model. */
    static final class Ticket extends Model {
        static final ModelQuery<Ticket> QUERY = ModelQuery.of(Ticket.class, Ticket::new);

        @Override
        protected UniqueIdKind uniqueIdKind() {
            return UniqueIdKind.UUID;
        }

        @Override
        protected List<String> fillable() {
            return Collections.singletonList("title");
        }
    }

    /** A ULID-keyed model whose timestamps are instants, on a table built with instant columns. */
    static final class Reading extends Model {
        static final ModelQuery<Reading> QUERY = ModelQuery.of(Reading.class, Reading::new);

        @Override
        protected UniqueIdKind uniqueIdKind() {
            return UniqueIdKind.ULID;
        }

        @Override
        protected TimestampFormat timestampFormat() {
            return TimestampFormat.INSTANT;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** A model with no updated_at column. */
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

    private java.sql.Connection jdbc;
    private QueryFactory db;
    private ModelQuery<Account> accounts;

    @BeforeEach
    void open() throws SQLException {
        jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
        Connection connection = Connection.builder().name("sqlite").connectionProvider(new SingleConnection(jdbc))
                .build();
        db = SQLiteQuery.of(connection);
        SQLiteSchema.of(connection).create("accounts", t -> {
            t.id();
            t.string("name");
            t.string("email").unique();
            t.integer("votes").nullable();
            t.timestamps();
        });
        SQLiteSchema.of(connection).create("tickets", t -> {
            t.uuid("id").primary();
            t.string("title");
            t.timestamps();
        });
        SQLiteSchema.of(connection).create("readings", t -> {
            t.ulid("id").primary();
            t.integer("value");
            t.instant("created_at").nullable();
            t.instant("updated_at").nullable();
        });
        SQLiteSchema.of(connection).create("log_lines", t -> {
            t.id();
            t.string("text");
            t.timestamp("created_at").nullable();
        });
        accounts = Account.QUERY.on(db).withClock(ModelClock.fixed(NOW));
    }

    @AfterEach
    void close() throws SQLException {
        jdbc.close();
    }

    private void seed(int count) {
        for (int i = 1; i <= count; i++) {
            accounts.create(row("name", "user" + i, "email", "u" + i + "@x", "votes", i));
        }
    }

    @Test
    void crudWithTextTimestampsOnATimestampsTable() {
        Account created = accounts.create(row("name", "Ann", "email", "a@x"));

        assertEquals(1L, created.getKey());
        Account found = accounts.findOrFail(1);
        assertEquals("Ann", found.getString("name"));
        assertEquals("2026-11-02 10:15:30", found.getAttribute("created_at"));
        assertEquals(NOW.minusNanos(123456000), found.getInstant("updated_at"));
        assertTrue(found.isClean());
        assertTrue(found.is(created));

        found.setAttribute("name", "Bob");
        assertTrue(found.save());
        assertEquals(Collections.singletonMap("name", "Bob"), found.getChanges());
        assertEquals("Bob", accounts.findOrFail(1).getAttribute("name"));
        assertTrue(found.save());

        assertTrue(found.delete());
        assertFalse(accounts.find(1).isPresent());
        assertFalse(found.delete());
    }

    @Test
    void storedValuesCompareCleanAgainstJavaValues() {
        accounts.create(row("name", "Ann", "email", "a@x", "votes", 5));
        Account found = accounts.findOrFail(1);

        found.setAttribute("votes", 5);
        found.setAttribute("id", 1);
        found.setAttribute("updated_at", Instant.parse("2026-11-02T10:15:30Z"));

        assertTrue(found.isClean());
        found.setAttribute("votes", "5.0");
        assertTrue(found.isDirty("votes"));
    }

    @Test
    void massAssignmentAndForceFill() {
        MassAssignmentException e = assertThrows(MassAssignmentException.class,
                () -> TestModels.BlogPost.QUERY.on(db).make(row("title", "x")));
        assertTrue(e.getMessage().startsWith("Add [title] to fillable property"));

        Account forced = accounts.forceCreate(row("name", "F", "email", "f@x", "id", 40));
        assertEquals(40L, forced.getKey());
    }

    @Test
    void refreshFreshAndReplicate() {
        Account account = accounts.create(row("name", "Ann", "email", "a@x"));
        db.table("accounts").where("id", 1).update(Collections.singletonMap("name", "Changed"));

        Account fresh = account.<Account>fresh().get();
        assertEquals("Changed", fresh.getAttribute("name"));
        assertEquals("Ann", account.getAttribute("name"));
        account.refresh();
        assertEquals("Changed", account.getAttribute("name"));

        Account copy = account.replicate("email");
        copy.setAttribute("email", "copy@x");
        assertTrue(copy.save());
        assertEquals(2L, copy.getKey());
        assertEquals(2, accounts.query().count());
    }

    @Test
    void incrementAndDecrementKeepTheModelInSync() {
        Account account = accounts.create(row("name", "Ann", "email", "a@x", "votes", 1));

        account.increment("votes", 4);
        account.decrement("votes");

        assertEquals(4L, account.getAttribute("votes"));
        assertTrue(account.isClean());
        assertEquals(4L, accounts.findOrFail(1).getLong("votes"));
        assertThrows(IllegalStateException.class, () -> accounts.newModel().increment("votes"));
    }

    @Test
    void finderFailuresUseLaravelsMessages() {
        seed(2);

        ModelNotFoundException many = assertThrows(ModelNotFoundException.class,
                () -> accounts.query().findOrFail(Arrays.asList(1, 2, 7, 9)));
        assertEquals("No query results for model [" + Account.class.getName() + "] 7, 9", many.getMessage());
        assertThrows(ModelNotFoundException.class, () -> accounts.where("name", "nobody").firstOrFail());
        assertThrows(MultipleRecordsFoundException.class, () -> accounts.query().sole());
        assertEquals("user2", accounts.where("votes", 2).sole().getAttribute("name"));
        assertEquals(2, accounts.query().findOrFail(Arrays.asList(1, 2)).size());
    }

    @Test
    void firstOrCreateReturnsTheRowSomeoneElseInsertedFirst() {
        Account first = accounts.firstOrCreate(row("email", "a@x"), row("name", "Ann"));
        assertTrue(first.wasRecentlyCreated());
        db.table("accounts").insert(row("name", "Other", "email", "race@x"));

        Account raced = accounts.query().createOrFirst(row("email", "race@x"), row("name", "Mine"));

        assertEquals("Other", raced.getAttribute("name"));
        assertFalse(raced.wasRecentlyCreated());
        assertEquals(2, accounts.query().count());
        Account again = accounts.firstOrCreate(row("email", "a@x"), row("name", "Ignored"));
        assertTrue(again.is(first));
    }

    @Test
    void createOrFirstInsideATransactionKeepsTheTransactionUsable() {
        db.table("accounts").insert(row("name", "Other", "email", "race@x"));

        db.connection().transaction(c -> {
            // Strict transactions refuse the root connection here; bind the models to the callback's.
            ModelQuery<Account> inTransaction = accounts.on(c);
            Account raced = inTransaction.query().createOrFirst(row("email", "race@x"), row("name", "Mine"));
            assertEquals("Other", raced.getAttribute("name"));
            inTransaction.create(row("name", "After", "email", "after@x"));
            return null;
        });

        assertEquals(2, accounts.query().count());
    }

    @Test
    void aRolledBackTransactionDiscardsTheModelItCreated() {
        accounts.create(row("name", "Ann", "email", "a@x"));

        assertThrows(IllegalStateException.class, () -> db.connection().transaction(c -> {
            assertEquals("Ann", accounts.on(c).find(1).get().getAttribute("name"));
            accounts.on(c).create(row("name", "Gone", "email", "gone@x"));
            assertEquals(2, accounts.on(c).query().count());
            throw new IllegalStateException("roll back");
        }));

        assertEquals(1, accounts.query().count());
        assertFalse(accounts.where("email", "gone@x").first().isPresent());
    }

    @Test
    void saveOrFailCommitsTheInsert() {
        Account account = accounts.make(row("name", "Ann", "email", "a@x"));

        assertTrue(account.saveOrFail());

        assertEquals(1, accounts.query().count());
        assertTrue(account.exists());
    }

    @Test
    void updateOrCreateAndBuilderWrites() {
        accounts.updateOrCreate(row("email", "a@x"), row("name", "Ann"));
        Account updated = accounts.updateOrCreate(row("email", "a@x"), row("name", "Zed"));

        assertEquals("Zed", accounts.findOrFail(updated.getKey()).getAttribute("name"));
        assertEquals(1, accounts.where("email", "a@x").update(row("votes", 9)));
        assertEquals(9L, accounts.findOrFail(1).getLong("votes"));
        assertEquals(1, accounts.query().increment("votes", 2));
        assertEquals(11L, accounts.findOrFail(1).getLong("votes"));
        assertEquals(1, accounts.query().whereKey(1).delete());
        assertEquals(0, accounts.query().count());
    }

    @Test
    void destroyDeletesEachModel() {
        seed(3);

        assertEquals(2, accounts.destroy(1, 3, 99));

        ModelCollection<Account> left = accounts.all();
        assertEquals(1, left.size());
        assertEquals(Long.valueOf(2), left.get(0).getLong("id"));
    }

    @Test
    void chunksLazyCursorAndPagesYieldModels() {
        seed(23);
        List<Object> chunked = new ArrayList<>();
        accounts.query().chunk(5, chunk -> chunked.addAll(chunk.modelKeys()));
        List<Object> byId = new ArrayList<>();
        accounts.query().chunkById(5, chunk -> byId.addAll(chunk.modelKeys()));
        List<Object> lazy = new ArrayList<>();
        for (Account account : accounts.query().lazyById(4)) {
            lazy.add(account.getKey());
        }
        List<Object> streamed = new ArrayList<>();
        try (ModelCursor<Account> cursor = accounts.query().orderBy("id").cursor()) {
            for (Account account : cursor) {
                assertTrue(account.exists());
                streamed.add(account.getKey());
            }
        }

        assertEquals(23, chunked.size());
        assertEquals(chunked, byId);
        assertEquals(chunked, lazy);
        assertEquals(chunked, streamed);
        ModelPage<Account> page = accounts.query().paginate(10, 3);
        assertEquals(23, page.total());
        assertEquals(3, page.items().size());
        assertEquals(15, accounts.query().paginate().items().size());
        assertTrue(accounts.query().simplePaginate(10, 2).hasMorePages());
        CursorModelPage<Account> first = accounts.query().cursorPaginate(10);
        CursorModelPage<Account> second = accounts.query().cursorPaginate(10, first.nextCursor().get());
        // SQLite reads small integers back as Integer; is() and find() still match them to Long keys.
        assertEquals(Long.valueOf(11), second.items().get(0).getLong("id"));
        assertTrue(second.items().contains(11L));
        assertTrue(second.items().fresh().get(0).is(second.items().get(0)));
    }

    @Test
    void uuidAndUlidKeysAreGeneratedOrKept() {
        Ticket ticket = Ticket.QUERY.on(db).create(row("title", "t"));
        Ticket preset = Ticket.QUERY.on(db).forceCreate(row("id", "my-id", "title", "p"));

        assertTrue(ticket.getString("id").matches("[0-9a-f-]{36}"));
        assertEquals("t", Ticket.QUERY.on(db).findOrFail(ticket.getKey()).getAttribute("title"));
        assertEquals("my-id", preset.getKey());
        assertTrue(Ticket.QUERY.on(db).find("my-id").isPresent());
    }

    @Test
    void instantTimestampsOnInstantColumns() {
        ModelQuery<Reading> readings = Reading.QUERY.on(db).withClock(ModelClock.fixed(NOW));
        Reading reading = readings.create(row("value", 3));

        Map<String, Object> raw = db.table("readings").first().get();
        Reading found = readings.findOrFail(reading.getKey());

        assertEquals(26, reading.getString("id").length());
        assertEquals(Instant.parse("2026-11-02T10:15:30.123456Z"), found.getInstant("created_at"));
        assertTrue(raw.get("created_at") instanceof Long);
        found.setAttribute("updated_at", NOW);
        assertTrue(found.isClean());
        found.setAttribute("value", 4);
        assertTrue(found.save());
        assertEquals(NOW, readings.findOrFail(reading.getKey()).getInstant("updated_at"));
    }

    @Test
    void aModelWithoutAnUpdatedAtColumnWritesOnlyCreatedAt() {
        LogLine line = LogLine.QUERY.on(db).create(row("text", "hello"));

        assertNull(line.getAttribute("updated_at"));
        assertTrue(line.getAttribute("created_at") instanceof String);
        line.setAttribute("text", "bye");
        assertTrue(line.save());
        assertNotEquals(null, LogLine.QUERY.on(db).findOrFail(1).getAttribute("created_at"));
    }

    /** Hands out one open in-memory connection and never closes it, so the database lives on. */
    static final class SingleConnection implements JdbcConnectionProvider {
        private final java.sql.Connection connection;

        SingleConnection(java.sql.Connection connection) {
            this.connection = connection;
        }

        @Override
        public java.sql.Connection acquire() {
            return connection;
        }

        @Override
        public void release(java.sql.Connection released) {
            // The test closes the one connection when it ends.
        }
    }
}
