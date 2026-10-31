package org.aristonis.diwan.database;

import static org.aristonis.diwan.database.FakeDriver.failure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ConnectionSessionTest {

    private final FakeDriver driver = new FakeDriver();
    private final Connection db = Connection.builder().name("main").driver(driver).build();

    @Test
    void everyCallInASessionUsesOneDriverSession() {
        String result = db.session(session -> {
            session.statement("pragma a");
            session.transaction(tx -> tx.statement("insert x"));
            session.statement("pragma b");
            return "done";
        });

        assertEquals("done", result);
        assertEquals(1, driver.sessionsOpened());
        assertEquals(Arrays.asList("open", "execute pragma a []", "begin", "execute insert x []", "commit",
                "execute pragma b []", "close"), driver.calls());
    }

    @Test
    void aFailedSessionDropsTheConnectionInsteadOfReturningIt() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> db.session(session -> {
            session.statement("pragma off");
            throw new IllegalStateException("could not restore");
        }));

        assertEquals("could not restore", thrown.getMessage());
        assertEquals("discard", driver.calls().get(driver.calls().size() - 1));
        assertEquals(0, driver.openSessions());
    }

    @Test
    void aFailedTransactionInsideASessionRollsBackAndKeepsTheSession() {
        driver.failsOn("insert bad", failure("23000"));

        db.session(session -> {
            assertThrows(QueryException.class, () -> session.transaction(tx -> tx.statement("insert bad")));
            session.statement("pragma after");
            return null;
        });

        assertEquals(Arrays.asList("open", "begin", "execute insert bad []", "rollback", "execute pragma after []",
                "close"), driver.calls());
    }

    @Test
    void aSessionTransactionRetriesOnTheSameSession() {
        driver.failsWithConcurrency("update x", 1);

        db.session(session -> session.transaction(tx -> tx.update("update x"), 2));

        assertEquals(1, driver.sessionsOpened());
        assertTrue(driver.calls().contains("commit"));
    }

    @Test
    void aSessionConnectionCannotBeUsedAfterItsCallback() {
        AtomicReference<Connection> leaked = new AtomicReference<>();
        db.session(session -> {
            leaked.set(session);
            return null;
        });

        assertThrows(IllegalStateException.class, () -> leaked.get().statement("late"));
    }

    @Test
    void aSessionRefusesScopesAndReportsLevelZero() {
        db.session(session -> {
            assertEquals(0, session.transactionLevel());
            assertThrows(UnsupportedOperationException.class, session::beginTransaction);
            assertEquals(1, session.pretend(p -> p.statement("x")).size());
            return null;
        });
    }

    @Test
    void insideATransactionASessionIsThatTransaction() {
        db.transaction(tx -> {
            assertSame(tx, tx.session(session -> session));
            return null;
        });
    }

    @Test
    void aSessionTransactionRefusesBadAttempts() {
        db.session(session -> {
            assertThrows(IllegalArgumentException.class, () -> session.transaction(tx -> null, 0));
            return null;
        });
        assertThrows(NullPointerException.class, () -> db.session(null));
    }
}
