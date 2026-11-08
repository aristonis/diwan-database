package org.aristonis.diwan.database.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.TransactionOptions;
import org.aristonis.diwan.database.TransactionScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** A joined transaction: the opening thread's root calls run on it, so closing it undoes them. */
class JoinedTransactionTest {

    private static final String COUNT = "select count(*) as n from t";

    @TempDir
    Path folder;

    private Connection db;

    @BeforeEach
    void connect() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("joined.db"));
        dataSource.setBusyTimeout(0);
        db = Connection.builder().name("sqlite").dataSource(dataSource).build();
        db.statement("create table t (id integer primary key, v)");
    }

    private long count() {
        return ((Number) db.select(COUNT).get(0).get("n")).longValue();
    }

    @Test
    void rootCallsSeeTheJoinedWorkAndCloseRollsItBack() {
        try (TransactionScope joined = db.beginJoinedTransaction()) {
            assertNotNull(joined);
            db.insert("insert into t (id, v) values (1, 'a')");

            assertEquals(1, count());
        }

        assertEquals(0, count());
    }

    @Test
    void commitKeepsTheWorkAndReleasesTheThread() {
        TransactionScope joined = db.beginJoinedTransaction();
        db.insert("insert into t (id, v) values (1, 'a')");
        joined.commit();
        joined.close();

        assertEquals(1, count());
        assertEquals(0, db.transactionLevel());
    }

    @Test
    void rootTransactionBecomesASavepointWhoseFailureRollsBackOnlyItsPart() {
        try (TransactionScope joined = db.beginJoinedTransaction()) {
            assertNotNull(joined);
            db.insert("insert into t (id, v) values (1, 'outer')");
            assertThrows(IllegalArgumentException.class, () -> db.transaction(tx -> {
                assertEquals(2, tx.transactionLevel());
                tx.insert("insert into t (id, v) values (2, 'inner')");
                throw new IllegalArgumentException("boom");
            }));

            assertEquals(1, count());
            assertEquals(1, db.transactionLevel());
        }

        assertEquals(0, count());
    }

    @Test
    void rootTransactionWithALockWaitIsRefusedAsANestedOne() {
        try (TransactionScope joined = db.beginJoinedTransaction()) {
            assertNotNull(joined);
            assertThrows(IllegalStateException.class,
                    () -> db.transaction(tx -> null, TransactionOptions.lockWait(Duration.ofMillis(100))));
        }
    }

    @Test
    void rootBeginTransactionOpensANestedScope() {
        try (TransactionScope joined = db.beginJoinedTransaction()) {
            assertNotNull(joined);
            try (TransactionScope nested = db.beginTransaction()) {
                assertNotNull(nested);
                db.insert("insert into t (id, v) values (1, 'a')");
                assertEquals(2, db.transactionLevel());
            }

            assertEquals(0, count());
            assertEquals(1, db.transactionLevel());
        }
    }

    @Test
    void otherThreadsKeepTheirOwnSessionAndDoNotSeeTheUncommittedRow() throws InterruptedException {
        AtomicReference<Long> seen = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (TransactionScope joined = db.beginJoinedTransaction()) {

            assertNotNull(joined);
            db.insert("insert into t (id, v) values (1, 'a')");
            Thread other = new Thread(() -> {
                try {
                    seen.set(count());
                } catch (RuntimeException | Error error) {
                    failure.set(error);
                }
            });
            other.start();
            other.join();
        }

        assertNull(failure.get());
        assertEquals(0L, seen.get());
    }

    @Test
    void anExceptionInsideStillReleasesTheThreadOnClose() {
        assertThrows(IllegalArgumentException.class, () -> {
            try (TransactionScope joined = db.beginJoinedTransaction()) {
                assertNotNull(joined);
                db.insert("insert into t (id, v) values (1, 'a')");
                throw new IllegalArgumentException("boom");
            }
        });

        assertEquals(0, count());
        db.insert("insert into t (id, v) values (2, 'b')");
        assertEquals(1, count());
        assertEquals(0, db.transactionLevel());
    }

    @Test
    void aSecondJoinOnTheSameThreadIsRefused() {
        try (TransactionScope joined = db.beginJoinedTransaction()) {
            assertNotNull(joined);
            IllegalStateException error = assertThrows(IllegalStateException.class, db::beginJoinedTransaction);

            assertTrue(error.getMessage().contains("joined transaction"));
        }

        try (TransactionScope again = db.beginJoinedTransaction()) {

            assertNotNull(again);
            assertEquals(1, db.transactionLevel());
        }
    }

    @Test
    void afterCommitCallbacksRegisteredInsideDoNotRunOnRollback() {
        AtomicBoolean fired = new AtomicBoolean();

        try (TransactionScope joined = db.beginJoinedTransaction()) {

            assertNotNull(joined);
            db.afterCommit(() -> fired.set(true));
            assertFalse(fired.get());
        }

        assertFalse(fired.get());
    }

    @Test
    void strictModeStillRefusesRootCallsInsideAnOrdinaryTransaction() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> db.transaction(tx -> db.select(COUNT)));

        assertTrue(error.getMessage().contains("transaction"));
    }

    @Test
    void connectionsInsideATransactionDoNotSupportJoining() {
        db.transaction(tx -> {
            assertThrows(UnsupportedOperationException.class, tx::beginJoinedTransaction);
            return null;
        });
    }
}
