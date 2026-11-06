package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.manager.ConnectionConfig;
import org.aristonis.diwan.database.manager.DatabaseConfig;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** Connector options and failure handling against a real PostgreSQL; skipped when it is not installed. */
class PostgresConnectionEdgesTest {

    private static final int WAIT_SECONDS = 30;
    private static final int ATTEMPTS = 5;

    private static DatabaseManager manager(ConnectionConfig config) {
        DatabaseConfig all = DatabaseConfig.builder().defaultConnection("main").connection(config).build();
        return DatabaseManager.builder().config(all).connector(new PostgresConnector()).build();
    }

    private static ConnectionConfig.Builder over(PGSimpleDataSource server, String database) {
        return ConnectionConfig.builder("main").driver("pgsql").host(server.getServerNames()[0])
                .port(server.getPortNumbers()[0]).database(database).username("postgres");
    }

    @Test
    void duplicateColumnLabelsAreRefusedAndTheLabelIsNamed() {
        Connection db = PostgresServer.newDatabase();

        QueryException error = assertThrows(QueryException.class, () -> db.select("select 1 as a, 2 as a"));

        assertTrue(error.getCause().getMessage().contains("a"));
    }

    @Test
    void theSearchPathOptionPicksTheSchemaTheConnectionStartsIn() {
        String database = PostgresServer.newDatabaseName();
        PGSimpleDataSource server = PostgresServer.dataSource(database);
        Connection.builder().name("admin").dataSource(server).build().statement("create schema tenant");

        try (DatabaseManager manager = manager(over(server, database).option("search_path", "tenant").build())) {
            assertEquals("tenant", manager.connection().scalar("select current_schema()").orElseThrow());
        }
    }

    @Test
    void theSchemaOptionIsAnAliasOfSearchPath() {
        String database = PostgresServer.newDatabaseName();
        PGSimpleDataSource server = PostgresServer.dataSource(database);
        Connection.builder().name("admin").dataSource(server).build().statement("create schema other");

        try (DatabaseManager manager = manager(over(server, database).option("schema", "other").build())) {
            assertEquals("other", manager.connection().scalar("select current_schema()").orElseThrow());
        }
    }

    @Test
    void withoutASearchPathTheConnectionStartsInPublic() {
        String database = PostgresServer.newDatabaseName();
        PGSimpleDataSource server = PostgresServer.dataSource(database);

        try (DatabaseManager manager = manager(over(server, database).build())) {
            assertEquals("public", manager.connection().scalar("select current_schema()").orElseThrow());
        }
    }

    @Test
    void driverParametersInAnExplicitUrlReachTheServer() {
        String database = PostgresServer.newDatabaseName();
        PGSimpleDataSource server = PostgresServer.dataSource(database);
        String url = "jdbc:postgresql://" + server.getServerNames()[0] + ":" + server.getPortNumbers()[0] + "/"
                + database + "?ApplicationName=diwan-test&tcpKeepAlive=true";

        try (DatabaseManager manager = manager(
                ConnectionConfig.builder("main").driver("pgsql").url(url).username("postgres").build())) {
            assertEquals("diwan-test", manager.connection().scalar("show application_name").orElseThrow());
        }
    }

    @Test
    void aRealDeadlockOnTwoThreadsIsRetriedAndBothTransactionsCommit() throws Exception {
        Connection db = PostgresServer.newDatabase();
        db.statement("create table counters (id integer primary key, v integer not null)");
        db.insert("insert into counters values (1, 0), (2, 0)");
        CyclicBarrier bothHoldTheirFirstRow = new CyclicBarrier(2);
        AtomicInteger attempts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> left = pool.submit(() -> crossUpdate(db, bothHoldTheirFirstRow, attempts, 1, 2));
            Future<?> right = pool.submit(() -> crossUpdate(db, bothHoldTheirFirstRow, attempts, 2, 1));
            left.get(WAIT_SECONDS, TimeUnit.SECONDS);
            right.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertTrue(attempts.get() >= 3, "the deadlock loser ran again");
        assertEquals(2, ((Number) db.scalar("select v from counters where id = 1").orElseThrow()).intValue());
        assertEquals(2, ((Number) db.scalar("select v from counters where id = 2").orElseThrow()).intValue());
    }

    private static void crossUpdate(Connection db, CyclicBarrier barrier, AtomicInteger attempts, int first,
            int second) {
        AtomicInteger mine = new AtomicInteger();
        db.transaction(tx -> {
            attempts.incrementAndGet();
            tx.update("update counters set v = v + 1 where id = ?", first);
            if (mine.incrementAndGet() == 1) {
                awaitQuietly(barrier);
            }
            return tx.update("update counters set v = v + 1 where id = ?", second);
        }, ATTEMPTS);
    }

    private static void awaitQuietly(CyclicBarrier barrier) {
        try {
            barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.BrokenBarrierException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }
}
