package org.aristonis.diwan.database.eloquent;

import org.aristonis.diwan.database.eloquent.exception.MassAssignmentException;
import org.aristonis.diwan.database.eloquent.exception.ModelNotFoundException;
import org.aristonis.diwan.database.eloquent.exception.MultipleRecordsFoundException;
import org.aristonis.diwan.database.eloquent.page.CursorModelPage;
import org.aristonis.diwan.database.eloquent.page.ModelPage;
import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.pgsql.PostgresSchema;
import org.aristonis.diwan.database.query.QueryCursor;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Models against a throwaway PostgreSQL; skipped when the server binaries are missing. */
class PostgresModelTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:15:30.123456Z");
    private static final Instant NOW_SECONDS = Instant.parse("2026-11-02T10:15:30Z");

    /** A text-timestamp model on a table built with timestamps(). */
    static final class Account extends Model {
        static final ModelQuery<Account> QUERY = ModelQuery.of(Account.class, Account::new);

        @Override
        protected List<String> fillable() {
            return Arrays.asList("name", "email", "votes");
        }
    }

    /** A ULID-keyed model whose timestamps are instants, on instant columns. */
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

    private QueryFactory db;
    private ModelQuery<Account> accounts;

    @BeforeEach
    void open() {
        Connection connection = PostgresServer.newDatabase();
        db = PostgresQuery.of(connection);
        PostgresSchema.of(connection).create("accounts", t -> {
            t.id();
            t.string("name");
            t.string("email").unique();
            t.integer("votes").nullable();
            t.timestamps();
        });
        PostgresSchema.of(connection).create("readings", t -> {
            t.ulid("id").primary();
            t.integer("value");
            t.instant("created_at").nullable();
            t.instant("updated_at").nullable();
        });
        PostgresSchema.of(connection).create("tickets", t -> {
            t.uuid("id").primary();
            t.string("title");
            t.timestamps();
        });
        accounts = Account.QUERY.on(db).withClock(ModelClock.fixed(NOW));
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
        assertTrue(found.getAttribute("created_at") instanceof LocalDateTime);
        assertEquals(NOW_SECONDS, found.getInstant("updated_at"));
        found.setAttribute("updated_at", "2026-11-02 10:15:30");
        found.setAttribute("votes", null);
        assertTrue(found.isClean());
        assertTrue(found.is(created));

        found.setAttribute("name", "Bob");
        assertTrue(found.save());
        assertEquals(Collections.singletonMap("name", "Bob"), found.getChanges());
        assertEquals("Bob", accounts.findOrFail(1).getAttribute("name"));
        found.refresh();
        assertTrue(found.isClean());

        assertTrue(found.delete());
        assertFalse(accounts.find(1).isPresent());
    }

    @Test
    void lockForUpdateRendersAndNeedsATransaction() {
        seed(2);

        assertEquals("select * from \"accounts\" where \"votes\" = ? for update",
                accounts.query().where("votes", 1).lockForUpdate().toSql());
        assertEquals("select * from \"accounts\" for share", accounts.query().sharedLock().toSql());
        assertThrows(IllegalStateException.class, () -> accounts.query().lockForUpdate().get());

        int locked = db.connection().transaction(c ->
                Account.QUERY.on(PostgresQuery.of(c)).query().lockForUpdate().get().size());
        assertEquals(2, locked);
    }

    @Test
    void massAssignmentAndFinderFailures() {
        seed(2);

        assertThrows(MassAssignmentException.class, () -> TestModels.BlogPost.QUERY.on(db).make(row("title", "x")));
        assertEquals(40L, accounts.forceCreate(row("name", "F", "email", "f@x", "id", 40)).getKey());
        assertThrows(ModelNotFoundException.class, () -> accounts.where("name", "nobody").firstOrFail());
        assertThrows(MultipleRecordsFoundException.class, () -> accounts.query().sole());
        assertEquals("user2", accounts.where("votes", 2).sole().getAttribute("name"));
    }

    @Test
    void instantTimestampsOnInstantColumns() {
        ModelQuery<Reading> readings = Reading.QUERY.on(db).withClock(ModelClock.fixed(NOW));
        Reading reading = readings.create(row("value", 3));

        Reading found = readings.findOrFail(reading.getKey());

        assertEquals(26, reading.getString("id").length());
        assertEquals(NOW, found.getInstant("created_at"));
        found.setAttribute("updated_at", NOW);
        assertTrue(found.isClean());
        found.setAttribute("value", 4);
        assertTrue(found.save());
        assertEquals(4L, readings.findOrFail(reading.getKey()).getLong("value"));
    }

    @Test
    void uuidKeysAreGeneratedOrKept() {
        ModelQuery<Ticket> tickets = Ticket.QUERY.on(db);
        Ticket ticket = tickets.create(row("title", "t"));

        assertTrue(ticket.getString("id").matches("[0-9a-f-]{36}"));
        assertEquals("t", tickets.findOrFail(ticket.getKey()).getAttribute("title"));
    }

    @Test
    void firstOrCreateAndCreateOrFirstInsideATransactionUseASavepoint() {
        db.table("accounts").insert(row("name", "Other", "email", "race@x"));

        db.connection().transaction(c -> {
            ModelQuery<Account> inTransaction = accounts.on(c);
            Account raced = inTransaction.query().createOrFirst(row("email", "race@x"), row("name", "Mine"));
            assertEquals("Other", raced.getAttribute("name"));
            assertFalse(raced.wasRecentlyCreated());
            Account made = inTransaction.firstOrCreate(row("email", "new@x"), row("name", "New"));
            assertTrue(made.wasRecentlyCreated());
            assertTrue(inTransaction.firstOrCreate(row("email", "new@x")).is(made));
            return null;
        });

        assertEquals(2, accounts.query().count());
    }

    @Test
    void aRolledBackTransactionDiscardsTheModelItCreated() {
        assertThrows(IllegalStateException.class, () -> db.connection().transaction(c -> {
            accounts.on(c).create(row("name", "Gone", "email", "gone@x"));
            throw new IllegalStateException("roll back");
        }));

        assertEquals(0, accounts.query().count());
    }

    @Test
    void chunksCursorAndPagesYieldModels() {
        seed(23);
        List<Object> chunked = new ArrayList<>();
        accounts.query().chunk(5, chunk -> chunked.addAll(chunk.modelKeys()));
        List<Object> streamed = new ArrayList<>();
        try (QueryCursor<Account> cursor = accounts.query().orderBy("id").cursor()) {
            for (Account account : cursor) {
                streamed.add(account.getKey());
            }
        }

        assertEquals(23, chunked.size());
        assertEquals(chunked, streamed);
        ModelPage<Account> page = accounts.query().paginate(10, 3);
        assertEquals(23, page.total());
        assertEquals(3, page.items().size());
        CursorModelPage<Account> first = accounts.query().cursorPaginate(10);
        CursorModelPage<Account> second = accounts.query().cursorPaginate(10, first.nextCursor().get());
        assertEquals(Long.valueOf(11), second.items().get(0).getLong("id"));
    }
}
