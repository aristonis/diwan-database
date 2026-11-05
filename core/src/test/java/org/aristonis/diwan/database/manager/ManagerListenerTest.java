package org.aristonis.diwan.database.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.ConnectionListener;
import org.aristonis.diwan.database.QueryExecuted;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.migrations.MigrationEvent;
import org.aristonis.diwan.database.migrations.MigrationListener;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.sqlite.SQLiteConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagerListenerTest {

    /** Counts the statements that failed, which is what the counting data source makes of every one. */
    private static final class FailureCounter implements ConnectionListener {
        private final AtomicInteger failed = new AtomicInteger();

        @Override
        public void queryExecuted(QueryExecuted event) {
            failed.incrementAndGet();
        }
    }

    private static DatabaseManager.Builder builder(FakeConnector connector, String... names) {
        return DatabaseManager.builder().config(ManagerFixtures.config(names)).connector(connector);
    }

    private static void runStatement(Connection connection) {
        // Pretend mode runs no SQL against the fake driver but still reports the statement.
        connection.pretend(recording -> recording.statement("select 1"));
    }

    @Test
    void connectionListenerIsAttachedToEveryBuiltConnection() {
        FakeConnector connector = new FakeConnector("fake");
        FailureCounter counter = new FailureCounter();
        DatabaseManager manager = builder(connector, "main", "other").listener(counter).build();

        runStatement(manager.connection("main"));
        runStatement(manager.connection("other"));

        assertEquals(2, counter.failed.get());
    }

    @Test
    void connectionEstablishedFiresForEachBuildAndAfterPurgeAndReconnect() {
        FakeConnector connector = new FakeConnector("fake");
        List<String> seen = new CopyOnWriteArrayList<>();
        FailureCounter counter = new FailureCounter();
        DatabaseManager manager = builder(connector, "main", "other").listener(counter)
                .managerListener(new ManagerListener() {
                    @Override
                    public void connectionEstablished(ConnectionEstablished event) {
                        seen.add(event.connectionName() + "/" + event.driver());
                    }
                }).build();

        Connection first = manager.connection("main");
        manager.connection("main");
        manager.connection("other");
        manager.purge("main");
        Connection rebuilt = manager.connection("main");
        Connection reconnected = manager.reconnect("other");

        assertEquals(Arrays.asList("main/fake", "other/fake", "main/fake", "other/fake"), seen);
        assertNotSame(first, rebuilt);
        runStatement(rebuilt);
        runStatement(reconnected);
        assertEquals(2, counter.failed.get());
    }

    @Test
    void connectionEstablishedSeesTheConnectionCallersReceive() {
        FakeConnector connector = new FakeConnector("fake");
        List<Connection> seen = new ArrayList<>();
        DatabaseManager manager = builder(connector, "main")
                .managerListener(new ManagerListener() {
                    @Override
                    public void connectionEstablished(ConnectionEstablished event) {
                        seen.add(event.connection());
                    }
                }).build();

        Connection connection = manager.connection();

        assertEquals(1, seen.size());
        assertSame(connection, seen.get(0));
    }

    @Test
    void failingConnectionEstablishedClosesTheConnectionAndTheNextCallRebuilds() {
        FakeConnector connector = new FakeConnector("fake");
        AtomicBoolean shouldFail = new AtomicBoolean(true);
        DatabaseManager manager = builder(connector, "main")
                .managerListener(new ManagerListener() {
                    @Override
                    public void connectionEstablished(ConnectionEstablished event) {
                        if (shouldFail.get()) {
                            throw new IllegalArgumentException("refused");
                        }
                    }
                }).build();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> manager.connection());

        assertEquals("refused", failure.getMessage());
        assertEquals(1, connector.closed().size());
        assertTrue(manager.getConnections().isEmpty());

        shouldFail.set(false);
        Connection connection = manager.connection();

        assertEquals(2, connector.connects());
        assertEquals(1, connector.closed().size());
        assertSame(connection, manager.connection());
    }

    @Test
    void askingForTheConnectionBeingBuiltIsRefusedWithoutDeadlock() {
        FakeConnector connector = new FakeConnector("fake");
        DatabaseManager[] holder = new DatabaseManager[1];
        DatabaseManager manager = builder(connector, "main", "other")
                .managerListener(new ManagerListener() {
                    @Override
                    public void connectionEstablished(ConnectionEstablished event) {
                        holder[0].connection("main");
                    }
                }).build();
        holder[0] = manager;

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> manager.connection("main"));

        assertTrue(failure.getMessage().contains("[main]"), failure.getMessage());
        assertEquals(1, connector.connects());
        assertEquals(1, connector.closed().size());
        assertTrue(manager.getConnections().isEmpty());
    }

    @Test
    void askingForAnotherConnectionInsideTheListenerIsAllowed() {
        FakeConnector connector = new FakeConnector("fake");
        List<String> seen = new ArrayList<>();
        DatabaseManager[] holder = new DatabaseManager[1];
        DatabaseManager manager = builder(connector, "main", "other")
                .managerListener(new ManagerListener() {
                    @Override
                    public void connectionEstablished(ConnectionEstablished event) {
                        seen.add(event.connectionName());
                        if (event.connectionName().equals("main")) {
                            holder[0].connection("other");
                        }
                    }
                }).build();
        holder[0] = manager;

        manager.connection("main");

        assertEquals(Arrays.asList("main", "other"), seen);
    }

    @Test
    void builderRefusesNullListeners() {
        DatabaseManager.Builder builder = DatabaseManager.builder();

        assertThrows(NullPointerException.class, () -> builder.listener(null));
        assertThrows(NullPointerException.class, () -> builder.managerListener(null));
        assertThrows(NullPointerException.class, () -> builder.migrationListener(null));
    }

    @Test
    void migratorReceivesTheManagersMigrationListeners(@TempDir Path folder) {
        List<String> ended = new ArrayList<>();
        List<String> second = new ArrayList<>();
        DatabaseConfig config = DatabaseConfig.builder().defaultConnection("main")
                .connection(ConnectionConfig.builder("main").driver(SQLiteConnector.DRIVER)
                        .database(folder.resolve("main.db").toString()).build())
                .build();
        DatabaseManager manager = DatabaseManager.builder().config(config).connector(new SQLiteConnector())
                .migrationListener(new MigrationListener() {
                    @Override
                    public void migrationEnded(MigrationEvent event) {
                        ended.add(event.name());
                    }
                })
                .migrationListener(new MigrationListener() {
                    @Override
                    public void migrationEnded(MigrationEvent event) {
                        second.add(event.name());
                    }
                }).build();

        try {
            manager.migrator("main").add(new Migration() {
                @Override
                public String name() {
                    return "2026_01_create_things";
                }

                @Override
                public void up(Schema schema) {
                    schema.create("things", table -> table.string("x"));
                }
            }).build().run();
        } finally {
            manager.close();
        }

        assertEquals(Arrays.asList("2026_01_create_things"), ended);
        assertEquals(ended, second);
    }
}
