package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.ConnectionListener;
import org.aristonis.diwan.database.QueryException;
import org.aristonis.diwan.database.QueryFailed;
import org.aristonis.diwan.database.manager.DatabaseConfig;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Diwan over a real HikariCP pool on a SQLite file: transactions, retries, purge, leaks and connection state. */
class SQLiteHikariPoolTest {

    private static final int POOL_SIZE = 4;
    private static final int WAIT_SECONDS = 20;
    private static final int ROUNDS = 40;

    @TempDir
    Path folder;

    private final List<HikariDataSource> pools = new ArrayList<>();

    @AfterEach
    void closePools() {
        for (HikariDataSource pool : pools) {
            pool.close();
        }
    }

    private HikariDataSource pool(String file, boolean isAutoCommit) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + folder.resolve(file) + "?busy_timeout=0");
        config.setMaximumPoolSize(POOL_SIZE);
        config.setAutoCommit(isAutoCommit);
        HikariDataSource pool = new HikariDataSource(config);
        pools.add(pool);
        return pool;
    }

    private static int active(HikariDataSource pool) {
        return pool.getHikariPoolMXBean().getActiveConnections();
    }

    private static int count(Connection db) {
        return ((Number) db.scalar("select count(*) from t").orElseThrow()).intValue();
    }

    @Test
    void aTransactionCommitsAndAFailedOneRollsBackThroughThePool() {
        HikariDataSource pool = pool("tx.db", true);
        Connection db = Connection.builder().name("pool").dataSource(pool).build();
        db.statement("create table t (id integer)");

        db.transaction(tx -> tx.insert("insert into t values (1)"));
        assertThrows(IllegalStateException.class, () -> db.transaction(tx -> {
            tx.insert("insert into t values (2)");
            throw new IllegalStateException("rule");
        }));

        assertEquals(1, count(db));
        assertEquals(0, active(pool));
    }

    @Test
    void aPoolHandingOutAutoCommitOffKeepsWorkDoneOutsideATransactionAndGetsTheStateBack() throws Exception {
        HikariDataSource pool = pool("state.db", false);
        Connection db = Connection.builder().name("pool").dataSource(pool).build();

        db.statement("create table t (id integer)");
        db.insert("insert into t values (1)");
        db.transaction(tx -> tx.insert("insert into t values (2)"));

        Connection reader = Connection.builder().name("reader").dataSource(pool("state.db", true)).build();
        assertEquals(2, count(reader));
        try (java.sql.Connection raw = pool.getConnection()) {
            assertFalse(raw.getAutoCommit());
        }
        assertEquals(0, active(pool));
    }

    @Test
    void noPoolConnectionStaysCheckedOutAfterManyFailedStatementsAndTransactions() {
        HikariDataSource pool = pool("leak.db", true);
        Connection db = Connection.builder().name("pool").dataSource(pool).build();
        db.statement("create table t (id integer primary key)");
        db.insert("insert into t values (1)");

        for (int i = 0; i < ROUNDS; i++) {
            assertThrows(QueryException.class, () -> db.insert("insert into t values (1)"));
            assertThrows(QueryException.class, () -> db.transaction(tx -> tx.insert("insert into t values (1)")));
            assertThrows(IllegalStateException.class, () -> db.transaction(tx -> {
                tx.insert("insert into t values (" + 1000 + ")");
                throw new IllegalStateException("rule");
            }));
        }

        assertEquals(0, active(pool));
        assertEquals(1, count(db));
    }

    @Test
    void aBusyDatabaseIsRetriedOnAFreshAttemptAfterTheOtherWriterCommits() throws Exception {
        HikariDataSource pool = pool("retry.db", true);
        Connection db = Connection.builder().name("pool").dataSource(pool).build();
        db.statement("create table t (id integer)");
        CountDownLatch writerHoldsTheLock = new CountDownLatch(1);
        CountDownLatch firstAttemptFailed = new CountDownLatch(1);
        CountDownLatch writerCommitted = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        db.events().addListener(new ConnectionListener() {
            @Override
            public void queryFailed(QueryFailed event) {
                firstAttemptFailed.countDown();
            }
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> writer = executor.submit(() -> {
                db.transaction(tx -> {
                    tx.insert("insert into t values (1)");
                    writerHoldsTheLock.countDown();
                    await(firstAttemptFailed);
                    return null;
                });
                writerCommitted.countDown();
            });
            await(writerHoldsTheLock);

            db.transaction(tx -> {
                if (attempts.incrementAndGet() > 1) {
                    await(writerCommitted);
                }
                return tx.insert("insert into t values (2)");
            }, 5);
            writer.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(2, attempts.get());
        assertEquals(2, count(db));
        assertEquals(0, active(pool));
    }

    @Test
    void purgeClosesThePoolAndTheNextCallGetsANewWorkingOne() {
        AtomicInteger builds = new AtomicInteger();
        List<HikariDataSource> built = new ArrayList<>();
        DatabaseConfig config = DatabaseConfig.builder().defaultConnection("main")
                .connection(ManagerFixtures.sqlite("main", folder.resolve("purge.db").toString())).build();
        try (DatabaseManager manager = DatabaseManager.builder().config(config).connector(new SQLiteConnector())
                .dataSource("main", () -> {
                    builds.incrementAndGet();
                    HikariDataSource fresh = pool("purge.db", true);
                    built.add(fresh);
                    return fresh;
                }).build()) {
            Connection old = manager.connection();
            old.statement("create table t (id integer)");
            old.insert("insert into t values (1)");

            manager.purge("main");
            Connection rebuilt = manager.connection();

            assertTrue(built.get(0).isClosed());
            assertNotSame(old, rebuilt);
            assertEquals(2, builds.get());
            assertEquals(1, count(rebuilt));
            assertFalse(built.get(1).isClosed());
        }
        assertTrue(built.get(1).isClosed());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for the other thread");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
