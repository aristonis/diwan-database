package org.aristonis.diwan.database.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.migrations.MigrationLock;
import org.aristonis.diwan.database.migrations.Migrator;
import org.junit.jupiter.api.Test;

class DatabaseManagerTest {

    @Test
    void buildsLazilyAndKeepsTheSameInstance() {
        FakeConnector connector = new FakeConnector("fake");
        DatabaseManager manager = ManagerFixtures.manager(connector, "main", "other");

        assertEquals(0, connector.connects());
        assertTrue(manager.getConnections().isEmpty());

        Connection first = manager.connection("main");

        assertSame(first, manager.connection());
        assertSame(first, manager.query().connection());
        assertSame(first, manager.schema().connection());
        assertEquals(1, connector.connects());
        assertEquals("[main]", manager.getConnections().toString());
    }

    @Test
    void passesTheConfigToTheConnector() {
        FakeConnector connector = new FakeConnector("fake");
        DatabaseConfig config = DatabaseConfig.builder().defaultConnection("main")
                .connection(ConnectionConfig.builder("main").driver("fake").prefix("p_").build()).build();

        DatabaseManager manager = DatabaseManager.builder().config(config).connector(connector).build();

        assertEquals("p_", manager.connection().getTablePrefix());
        assertEquals("main", connector.lastConfig().name());
    }

    @Test
    void unknownConnectionNameListsTheKnownOnesSorted() {
        DatabaseManager manager = ManagerFixtures.manager(new FakeConnector("fake"), "zeta", "alpha");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> manager.connection("nope"));

        assertTrue(failure.getMessage().contains("[nope]"));
        assertTrue(failure.getMessage().contains("[alpha, zeta]"));
    }

    @Test
    void unknownDriverListsTheRegisteredOnesSorted() {
        DatabaseConfig config = DatabaseConfig.builder().defaultConnection("main")
                .connection(ConnectionConfig.builder("main").driver("ghost").build()).build();
        DatabaseManager manager = DatabaseManager.builder().config(config)
                .connector(new FakeConnector("zed"), new FakeConnector("abc")).build();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, manager::connection);

        assertTrue(failure.getMessage().contains("[ghost]"));
        assertTrue(failure.getMessage().contains("[abc, zed]"));
    }

    @Test
    void purgeThenConnectionRebuildsANewInstanceAndClosesTheOld() {
        FakeConnector connector = new FakeConnector("fake");
        DatabaseManager manager = ManagerFixtures.manager(connector, "main");
        Connection old = manager.connection();

        manager.purge("main");
        Connection rebuilt = manager.connection();

        assertNotSame(old, rebuilt);
        assertEquals(2, connector.connects());
        assertEquals(1, connector.closed().size());
        assertSame(old, connector.closed().get(0));
    }

    @Test
    void purgeOfANeverBuiltNameDoesNothing() {
        FakeConnector connector = new FakeConnector("fake");
        DatabaseManager manager = ManagerFixtures.manager(connector, "main", "other");

        manager.purge("other");

        assertEquals(0, connector.connects());
        assertTrue(connector.closed().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> manager.purge("nope"));
    }

    @Test
    void purgeWhileTheConnectionIsInUseLeavesTheHolderForTheNewOneAndClosesTheOldOnce() {
        FakeConnector connector = new FakeConnector("fake");
        DatabaseManager manager = ManagerFixtures.manager(connector, "main");
        Connection inUse = manager.connection();

        manager.purge("main");
        manager.purge("main");

        assertEquals(1, connector.closed().size());
        assertNotSame(inUse, manager.reconnect("main"));
        assertEquals(2, connector.connects());
        assertEquals("[main]", manager.getConnections().toString());
    }

    @Test
    void disconnectBehavesLikePurge() {
        FakeConnector connector = new FakeConnector("fake");
        DatabaseManager manager = ManagerFixtures.manager(connector, "main");
        manager.connection();

        manager.disconnect("main");

        assertTrue(manager.getConnections().isEmpty());
        assertEquals(1, connector.closed().size());
    }

    @Test
    void aRegisteredDataSourceReachesTheConnectorAndIsClosedAfterIt() {
        FakeConnector connector = new FakeConnector("fake");
        CountingDataSource source = new CountingDataSource();
        AtomicInteger supplied = new AtomicInteger();
        DatabaseManager manager = DatabaseManager.builder().config(ManagerFixtures.config("main"))
                .connector(connector).dataSource("main", () -> {
                    supplied.incrementAndGet();
                    return source;
                }).build();

        manager.connection();
        manager.connection();

        assertEquals(1, supplied.get());
        assertSame(source, connector.sources().get(0));
        manager.purge("main");
        assertEquals(1, source.closes());
    }

    @Test
    void aFailedBuildCachesNothingAndClosesTheRegisteredDataSource() {
        FakeConnector connector = new FakeConnector("fake").beforeConnect(config -> {
            throw new IllegalStateException("boom");
        });
        CountingDataSource source = new CountingDataSource();
        DatabaseManager manager = DatabaseManager.builder().config(ManagerFixtures.config("main"))
                .connector(connector).dataSource("main", () -> source).build();

        assertThrows(IllegalStateException.class, manager::connection);

        assertEquals(1, source.closes());
        assertTrue(manager.getConnections().isEmpty());
    }

    @Test
    void aDataSourceSupplierReturningNullFails() {
        DatabaseManager manager = DatabaseManager.builder().config(ManagerFixtures.config("main"))
                .connector(new FakeConnector("fake")).dataSource("main", () -> null).build();

        assertThrows(IllegalStateException.class, manager::connection);
    }

    @Test
    void closeIsIdempotentAndClosesEveryConnection() {
        FakeConnector connector = new FakeConnector("fake");
        DatabaseManager manager = ManagerFixtures.manager(connector, "a", "b");
        manager.connection("a");
        manager.connection("b");

        manager.close();
        manager.close();

        assertEquals(2, connector.closed().size());
    }

    @Test
    void useAfterCloseFails() {
        DatabaseManager manager = ManagerFixtures.manager(new FakeConnector("fake"), "main");
        manager.close();

        assertThrows(IllegalStateException.class, manager::connection);
        assertThrows(IllegalStateException.class, () -> manager.query("main"));
        assertThrows(IllegalStateException.class, () -> manager.purge("main"));
        assertThrows(IllegalStateException.class, manager::getDefaultConnection);
    }

    @Test
    void closeKeepsClosingWhenOneConnectorFailsAndThrowsTheFirstFailure() {
        DatabaseManager manager = DatabaseManager.builder().config(ManagerFixtures.config("a", "b"))
                .connector(new ThrowingCloseConnector()).build();
        manager.connection("a");
        manager.connection("b");

        IllegalStateException failure = assertThrows(IllegalStateException.class, manager::close);

        assertEquals(1, failure.getSuppressed().length);
    }

    @Test
    void setDefaultConnectionChangesWhatConnectionReturnsAndRejectsUnknownNames() {
        DatabaseManager manager = ManagerFixtures.manager(new FakeConnector("fake"), "a", "b");

        manager.setDefaultConnection("b");

        assertEquals("b", manager.getDefaultConnection());
        assertSame(manager.connection("b"), manager.connection());
        assertThrows(IllegalArgumentException.class, () -> manager.setDefaultConnection("zzz"));
    }

    @Test
    void migratorUsesTheConnectionsSchemaAndTheConnectorsLock() {
        AtomicInteger acquired = new AtomicInteger();
        FakeConnector connector = new FakeConnector("fake").withLock(new MigrationLock() {
            @Override
            public void acquire() {
                acquired.incrementAndGet();
            }

            @Override
            public void release() {
            }
        });
        DatabaseManager manager = ManagerFixtures.manager(connector, "main");

        Migrator.Builder builder = manager.migrator("main");

        assertNotNull(builder);
        assertNotNull(builder.build());
        assertEquals(0, acquired.get());
    }

    @Test
    void builderRejectsBadSetups() {
        FakeConnector one = new FakeConnector("fake");
        DatabaseManager.Builder noConfig = DatabaseManager.builder();

        assertThrows(IllegalStateException.class, noConfig::build);
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseManager.builder().connector(one, new FakeConnector("fake")));
        assertThrows(IllegalArgumentException.class, () -> DatabaseManager.builder()
                .config(ManagerFixtures.config("main")).dataSource("ghost", CountingDataSource::new).build());
    }

    /** Fails every close, to show the manager still closes the rest. */
    private static final class ThrowingCloseConnector implements Connector {

        private final FakeConnector delegate = new FakeConnector("fake");

        @Override
        public String driver() {
            return delegate.driver();
        }

        @Override
        public Connection connect(ConnectionConfig config, javax.sql.DataSource dataSource) {
            return delegate.connect(config, dataSource);
        }

        @Override
        public org.aristonis.diwan.database.query.QueryFactory query(Connection connection) {
            return delegate.query(connection);
        }

        @Override
        public org.aristonis.diwan.database.schema.Schema schema(Connection connection) {
            return delegate.schema(connection);
        }

        @Override
        public void close(Connection connection) {
            throw new IllegalStateException("close failed");
        }
    }
}
