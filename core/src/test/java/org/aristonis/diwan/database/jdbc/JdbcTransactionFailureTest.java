package org.aristonis.diwan.database.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.QueryException;
import org.aristonis.diwan.database.driver.DriverException;
import org.aristonis.diwan.database.driver.DriverFailureKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite failures at the end of a transaction and between two connections. */
class JdbcTransactionFailureTest {

    @TempDir
    Path folder;

    @Test
    void aCommitThatFailsRollsBackAndRestoresTheConnection() {
        CountingDataSource dataSource = new CountingDataSource(folder.resolve("fk.db").toString()).withForeignKeys();
        Connection db = Connection.builder().name("sqlite").dataSource(dataSource).build();
        db.statement("create table parents (id integer primary key)");
        db.statement("create table kids (parent_id integer references parents (id) deferrable initially deferred)");

        assertThrows(QueryException.class, () -> db.transaction(tx -> tx.insert("insert into kids values (99)")));

        assertEquals(0, dataSource.openConnections());
        assertEquals(0, dataSource.returnedWithoutAutoCommit());
        assertEquals(0L, ((Number) db.scalar("select count(*) from kids").get()).longValue());
        db.transaction(tx -> tx.insert("insert into parents values (1)"));
        assertEquals(1L, ((Number) db.scalar("select count(*) from parents").get()).longValue());
    }

    @Test
    void aBusyDatabaseIsAConcurrencyFailure() throws Exception {
        String file = folder.resolve("busy.db").toString();
        Connection writer = Connection.builder().name("a").dataSource(new CountingDataSource(file)).build();
        Connection other = Connection.builder().name("b").dataSource(new CountingDataSource(file).withoutBusyWait())
                .build();
        writer.statement("create table t (x integer)");
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> hold = pool.submit(() -> writer.transaction(tx -> {
                tx.insert("insert into t values (1)");
                holding.countDown();
                try {
                    done.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertTrue(holding.await(10, TimeUnit.SECONDS));

            QueryException error = assertThrows(QueryException.class, () -> other.insert("insert into t values (2)"));

            done.countDown();
            hold.get(10, TimeUnit.SECONDS);
            DriverException cause = (DriverException) error.getCause();
            assertTrue(cause.kinds().contains(DriverFailureKind.CONCURRENCY), cause.kinds().toString());
        } finally {
            done.countDown();
            pool.shutdownNow();
        }
    }
}
