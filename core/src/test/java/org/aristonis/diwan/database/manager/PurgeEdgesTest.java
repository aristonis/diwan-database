package org.aristonis.diwan.database.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.Processor;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.query.QueryGrammar;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.schema.SchemaGrammar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** Purging a connection with an open transaction, and purging one whose pool is dead. */
class PurgeEdgesTest {

    @TempDir
    Path folder;

    /** A connector that builds a connection over the data source it is given. */
    private static final class SourceConnector implements Connector {
        @Override
        public String driver() {
            return ManagerFixtures.DRIVER;
        }

        @Override
        public Connection connect(ConnectionConfig config, DataSource dataSource) {
            return Connection.builder().name(config.name()).dataSource(dataSource).build();
        }

        @Override
        public QueryFactory query(Connection connection) {
            return QueryFactory.of(connection, new QueryGrammar(connection.getTablePrefix()) { }, new Processor());
        }

        @Override
        public Schema schema(Connection connection) {
            return Schema.of(connection, new SchemaGrammar() { });
        }
    }

    /** A pool whose shutdown fails, as a pool does when its connections are already dead. */
    private static final class DeadPool extends SQLiteDataSource implements AutoCloseable {
        @Override
        public void close() {
            throw new IllegalStateException("the pool is dead");
        }
    }

    private SQLiteDataSource file(String name) {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + folder.resolve(name));
        return source;
    }

    @Test
    void purgeInsideAnOpenTransactionLetsItFinishAndRunItsCallbacksOnce() {
        SQLiteDataSource source = file("tx.db");
        DatabaseManager manager = DatabaseManager.builder().config(ManagerFixtures.config("main"))
                .connector(new SourceConnector()).dataSource("main", () -> source).build();
        Connection old = manager.connection();
        old.statement("create table t (id integer)");
        List<String> ran = new ArrayList<>();

        old.transaction(tx -> {
            tx.afterCommit(() -> ran.add("commit"));
            tx.insert("insert into t values (1)");
            manager.purge("main");
            return null;
        });

        assertEquals(Arrays.asList("commit"), ran);
        assertEquals(1, ((Number) old.scalar("select count(*) from t").orElseThrow()).intValue());
    }

    @Test
    void theConnectionBuiltAfterAPurgeInsideATransactionStartsClean() {
        SQLiteDataSource source = file("fresh.db");
        DatabaseManager manager = DatabaseManager.builder().config(ManagerFixtures.config("main"))
                .connector(new SourceConnector()).dataSource("main", () -> source).build();
        Connection old = manager.connection();
        List<String> ran = new ArrayList<>();

        old.transaction(tx -> {
            tx.afterCommit(() -> ran.add("old"));
            manager.purge("main");
            Connection fresh = manager.connection();
            assertNotSame(old, fresh);
            assertEquals(0, fresh.transactionLevel());
            fresh.afterCommit(() -> ran.add("fresh"));
            return null;
        });

        assertEquals(Arrays.asList("fresh", "old"), ran);
    }

    @Test
    void purgeOfADeadPoolReportsTheFailureAndTheNextCallGetsAWorkingConnection() {
        AtomicInteger builds = new AtomicInteger();
        DatabaseManager manager = DatabaseManager.builder().config(ManagerFixtures.config("main"))
                .connector(new SourceConnector())
                .dataSource("main", () -> builds.incrementAndGet() == 1 ? deadPool() : file("alive.db")).build();
        Connection dead = manager.connection();

        assertThrows(IllegalStateException.class, () -> manager.purge("main"));
        Connection rebuilt = manager.connection();

        assertNotSame(dead, rebuilt);
        assertEquals(1, ((Number) rebuilt.scalar("select 1").orElseThrow()).intValue());
        assertEquals(2, builds.get());
    }

    @Test
    void closeOfADeadPoolReportsTheFailureOnceAndALaterCloseDoesNothing() {
        DatabaseManager manager = DatabaseManager.builder().config(ManagerFixtures.config("a", "b"))
                .connector(new SourceConnector())
                .dataSource("a", PurgeEdgesTest::deadPool)
                .dataSource("b", () -> file("b.db")).build();
        manager.connection("a");
        manager.connection("b");

        IllegalStateException failure = assertThrows(IllegalStateException.class, manager::close);

        assertEquals(0, failure.getSuppressed().length);
        manager.close();
    }

    private static DeadPool deadPool() {
        DeadPool pool = new DeadPool();
        pool.setUrl("jdbc:sqlite::memory:");
        return pool;
    }
}
