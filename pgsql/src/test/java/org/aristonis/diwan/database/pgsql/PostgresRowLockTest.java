package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.query.LengthAwarePage;
import org.aristonis.diwan.database.query.LockStrength;
import org.aristonis.diwan.database.query.LockWait;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.Test;

/** Row locks against a real PostgreSQL server: two sessions, a transaction each. */
class PostgresRowLockTest {

    private static final int WAIT_SECONDS = 30;

    private static Connection items() {
        Connection db = PostgresServer.newDatabase();
        PostgresSchema.of(db).create("items", t -> {
            t.id();
            t.string("name");
        });
        QueryFactory factory = PostgresQuery.of(db);
        for (String name : List.of("a", "b", "c")) {
            factory.table("items").insert(java.util.Map.of("name", name));
        }
        return db;
    }

    /** Runs the work in a second transaction on another thread and returns what it throws, or null. */
    private static Throwable inAnotherSession(Connection db, java.util.function.Consumer<QueryFactory> work)
            throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            return pool.submit(() -> {
                try {
                    db.transaction(c -> {
                        work.accept(PostgresQuery.of(c));
                        return null;
                    });
                    return (Throwable) null;
                } catch (RuntimeException e) {
                    return e;
                }
            }).get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void lockForUpdateInATransactionStopsASecondSessionTakingTheSameRow() throws Exception {
        Connection db = items();

        Throwable second = db.transaction(c -> {
            assertEquals(1, PostgresQuery.of(c).table("items").where("id", "=", 1).lockForUpdate().get().size());
            try {
                return inAnotherSession(db, other -> other.table("items").where("id", "=", 1)
                        .lock(LockStrength.UPDATE, LockWait.NOWAIT).get());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertInstanceOf(QueryException.class, second);
    }

    @Test
    void skipLockedLeavesTheLockedRowOut() throws Exception {
        Connection db = items();
        int[] seen = new int[1];

        db.transaction(c -> {
            PostgresQuery.of(c).table("items").where("id", "=", 1).lockForUpdate().get();
            try {
                inAnotherSession(db, other -> seen[0] = other.table("items")
                        .lock(LockStrength.UPDATE, LockWait.SKIP_LOCKED).get().size());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });

        assertEquals(2, seen[0]);
    }

    @Test
    void twoSharedLocksOnTheSameRowDoNotBlockEachOther() throws Exception {
        Connection db = items();

        Throwable second = db.transaction(c -> {
            PostgresQuery.of(c).table("items").where("id", "=", 1).sharedLock().get();
            try {
                return inAnotherSession(db, other -> other.table("items").where("id", "=", 1)
                        .lock(LockStrength.SHARE, LockWait.NOWAIT).get());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertNull(second);
    }

    @Test
    void aLockedSelectOutsideATransactionIsRefused() {
        QueryFactory factory = PostgresQuery.of(items());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> factory.table("items").lockForUpdate().get());

        assertEquals("A row lock outside a transaction is released at once; run the query inside transaction(...)",
                error.getMessage());
    }

    @Test
    void countExistsAndPaginateWorkOnALockedQuery() {
        Connection db = items();

        LengthAwarePage page = db.transaction(c -> {
            QueryFactory factory = PostgresQuery.of(c);
            assertEquals(3L, factory.table("items").lockForUpdate().count());
            assertEquals(true, factory.table("items").lockForUpdate().exists());
            return factory.table("items").orderBy("id").lockForUpdate().paginate(2, 1);
        });

        assertEquals(3L, page.total());
        assertEquals(2, page.items().size());
    }
}
