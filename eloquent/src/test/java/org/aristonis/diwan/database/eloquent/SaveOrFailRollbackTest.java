package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** saveOrFail puts the model back as it was when its transaction rolls back. */
class SaveOrFailRollbackTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:15:30Z");

    /** An account whose created and saved hooks can be told to fail. */
    static final class Account extends Model {
        static final ModelQuery<Account> QUERY = ModelQuery.of(Account.class, Account::new);
        boolean failCreated;
        boolean failSaved;
        Runnable onCreated = () -> { };

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }

        @Override
        protected void created() {
            onCreated.run();
            if (failCreated) {
                throw new IllegalStateException("created failed");
            }
        }

        @Override
        protected void saved() {
            if (failSaved) {
                throw new IllegalStateException("saved failed");
            }
        }
    }

    /** A UUID-keyed ticket whose created hook fails. */
    static final class Ticket extends Model {
        static final ModelQuery<Ticket> QUERY = ModelQuery.of(Ticket.class, Ticket::new);

        @Override
        protected UniqueIdKind uniqueIdKind() {
            return UniqueIdKind.UUID;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected void created() {
            if ("fail".equals(getAttribute("title"))) {
                throw new IllegalStateException("created failed");
            }
        }
    }

    private java.sql.Connection jdbc;
    private QueryFactory db;
    private ModelQuery<Account> accounts;

    @BeforeEach
    void open() throws SQLException {
        jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
        Connection connection = Connection.builder().name("sqlite")
                .connectionProvider(new SQLiteModelTest.SingleConnection(jdbc)).build();
        db = SQLiteQuery.of(connection);
        SQLiteSchema.of(connection).create("accounts", t -> {
            t.id();
            t.string("name");
            t.timestamps();
        });
        SQLiteSchema.of(connection).create("tickets", t -> {
            t.uuid("id").primary();
            t.string("title");
        });
        accounts = Account.QUERY.on(db).withClock(ModelClock.fixed(NOW));
    }

    @AfterEach
    void close() throws SQLException {
        jdbc.close();
    }

    @Test
    void aCreatedHookFailureLeavesTheModelNewWithNoKeyAndNoRow() {
        Account account = accounts.make(row("name", "Ann"));
        account.failCreated = true;

        IllegalStateException e = assertThrows(IllegalStateException.class, account::saveOrFail);

        assertEquals("created failed", e.getMessage());
        assertFalse(account.exists());
        assertFalse(account.wasRecentlyCreated());
        assertNull(account.getKey());
        assertFalse(account.hasAttribute("created_at"));
        assertEquals(row("name", "Ann"), account.getAttributes());
        assertTrue(account.isDirty("name"));
        assertEquals(0, accounts.query().count());
    }

    @Test
    void aSavedHookFailureOnUpdatePutsTheAttributesBack() {
        Account account = accounts.create(row("name", "Ann"));
        Map<String, Object> before = account.getAttributes();
        Map<String, Object> originalBefore = account.getOriginal();
        account.setAttribute("name", "Bob");
        account.failSaved = true;

        assertThrows(IllegalStateException.class, account::saveOrFail);

        Map<String, Object> expected = new java.util.LinkedHashMap<>(before);
        expected.put("name", "Bob");
        assertEquals(expected, account.getAttributes());
        assertEquals(originalBefore, account.getOriginal());
        assertTrue(account.isDirty("name"));
        assertTrue(account.getChanges().isEmpty());
        assertTrue(account.exists());
        assertEquals("Ann", accounts.findOrFail(account.getKey()).getAttribute("name"));
    }

    @Test
    void aGeneratedUniqueIdIsClearedAfterTheRollback() {
        Ticket ticket = Ticket.QUERY.on(db).make(row("title", "fail"));

        assertThrows(IllegalStateException.class, ticket::saveOrFail);

        assertNull(ticket.getKey());
        assertFalse(ticket.exists());
        assertEquals(0, Ticket.QUERY.on(db).query().count());
    }

    @Test
    void aUniqueIdOfZeroIsKeptOnInsert() {
        Ticket ticket = Ticket.QUERY.on(db).forceCreate(row("id", "0", "title", "zero"));

        assertEquals("0", ticket.getKey());
        assertTrue(Ticket.QUERY.on(db).find("0").isPresent());
    }

    @Test
    void aNestedSaveOrFailOnTheRootConnectionIsRefusedAndBothModelsAreRestored() {
        Account inner = accounts.make(row("name", "Inner"));
        Account outer = accounts.make(row("name", "Outer"));
        outer.onCreated = inner::saveOrFail;

        IllegalStateException e = assertThrows(IllegalStateException.class, outer::saveOrFail);

        assertTrue(e.getMessage().startsWith("This thread is inside a transaction on connection 'sqlite'"));
        assertFalse(outer.exists());
        assertNull(outer.getKey());
        assertFalse(inner.exists());
        assertNull(inner.getKey());
        assertEquals(0, accounts.query().count());
    }

    @Test
    void aNestedSaveOrFailOnTheTransactionsConnectionKeepsItsStateWhenTheOuterRollsBack() {
        Account outer = accounts.make(row("name", "Outer"));
        Account[] inner = new Account[1];
        outer.onCreated = () -> {
            inner[0] = accounts.on(outer.getConnection()).make(row("name", "Inner"));
            inner[0].saveOrFail();
        };
        outer.failSaved = true;

        assertThrows(IllegalStateException.class, outer::saveOrFail);

        assertFalse(outer.exists());
        assertNull(outer.getKey());
        assertEquals(0, accounts.query().count());
        // Only the model whose saveOrFail failed is restored; the inner save succeeded, so its
        // model still reports the row the outer rollback discarded.
        assertTrue(inner[0].exists());
    }
}
