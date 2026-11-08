package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.TransactionOptions;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.query.LockStrength;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.Test;

/** The timed row lock {@code lock(strength, Duration)} against a real PostgreSQL server. */
class PostgresStatementLockWaitTest {

    private static final int WAIT_SECONDS = 30;

    private static Connection items() {
        Connection db = PostgresServer.newDatabase();
        PostgresSchema.of(db).create("items", t -> {
            t.id();
            t.string("name");
        });
        PostgresQuery.of(db).table("items").insert(Map.of("name", "a"));
        return db;
    }

    @Test
    void theLimitEndsWithTheStatement() {
        Connection db = items();

        String shown = db.transaction(c -> {
            PostgresQuery.of(c).table("items").lock(LockStrength.UPDATE, Duration.ofMillis(250)).get();
            return String.valueOf(c.select("show lock_timeout", List.of()).get(0).get("lock_timeout"));
        });

        assertEquals("0", shown);
    }

    @Test
    void theStatementLimitGivesBackTheTransactionLockWait() {
        Connection db = items();

        String shown = db.transaction(c -> {
            PostgresQuery.of(c).table("items").lock(LockStrength.UPDATE, Duration.ofMillis(250)).get();
            return String.valueOf(c.select("show lock_timeout", List.of()).get(0).get("lock_timeout"));
        }, TransactionOptions.lockWait(Duration.ofSeconds(5)));

        assertEquals("5s", shown);
    }

    @Test
    void theLimitEndsWithTheTransaction() {
        Connection db = items();

        db.transaction(c -> PostgresQuery.of(c).table("items").lock(LockStrength.UPDATE, Duration.ofMillis(250)).get());
        Object shown = db.select("show lock_timeout", List.of()).get(0).get("lock_timeout");

        assertEquals("0", String.valueOf(shown));
    }

    @Test
    void aTimedLockFailsWhenAnotherSessionKeepsTheRow() throws Exception {
        Connection db = items();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Throwable second = db.transaction(c -> {
                PostgresQuery.of(c).table("items").lockForUpdate().get();
                try {
                    return pool.submit(() -> {
                        try {
                            db.transaction(other -> PostgresQuery.of(other).table("items")
                                    .lock(LockStrength.UPDATE, Duration.ofMillis(200)).get());
                            return (Throwable) null;
                        } catch (RuntimeException e) {
                            return e;
                        }
                    }).get(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            assertInstanceOf(QueryException.class, second);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void statementOutsideTx() {
        QueryFactory factory = PostgresQuery.of(items());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> factory.table("items").lock(LockStrength.UPDATE, Duration.ofSeconds(1)).get());

        assertEquals("A lock wait timeout on a statement needs an open transaction", error.getMessage());
    }
}
