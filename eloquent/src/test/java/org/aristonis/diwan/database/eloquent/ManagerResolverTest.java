package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.TestModels.Person;
import org.aristonis.diwan.database.eloquent.TestModels.User;
import org.aristonis.diwan.database.manager.ConnectionConfig;
import org.aristonis.diwan.database.manager.Connector;
import org.aristonis.diwan.database.manager.DatabaseConfig;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.aristonis.diwan.database.migrations.MigrationLock;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.sqlite.SQLiteConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A resolver backed by a manager builds each connection when a model first reaches it. */
class ManagerResolverTest {

    /** The SQLite connector, counting the connections it is asked to build. */
    private static final class CountingConnector implements Connector {
        private final SQLiteConnector delegate = new SQLiteConnector();
        final AtomicInteger connects = new AtomicInteger();

        @Override
        public String driver() {
            return delegate.driver();
        }

        @Override
        public Connection connect(ConnectionConfig config, DataSource dataSource) {
            connects.incrementAndGet();
            return delegate.connect(config, dataSource);
        }

        @Override
        public QueryFactory query(Connection connection) {
            return delegate.query(connection);
        }

        @Override
        public Schema schema(Connection connection) {
            return delegate.schema(connection);
        }

        @Override
        public Optional<MigrationLock> lock(ConnectionConfig config, DataSource dataSource) {
            return delegate.lock(config, dataSource);
        }

        @Override
        public void close(Connection connection) {
            delegate.close(connection);
        }
    }

    private final CountingConnector connector = new CountingConnector();
    private DatabaseManager manager;

    @BeforeEach
    void open() {
        DatabaseConfig config = DatabaseConfig.builder().defaultConnection("main")
                .connection(ConnectionConfig.builder("main").driver("sqlite").database(":memory:").build())
                .connection(ConnectionConfig.builder("crm").driver("sqlite").database(":memory:").build())
                .build();
        manager = DatabaseManager.builder().config(config).connector(connector).build();
    }

    @AfterEach
    void close() {
        manager.close();
    }

    private void createTables() {
        manager.connection("main").statement(
                "create table users (id integer primary key autoincrement, name text, email text,"
                        + " created_at text, updated_at text)");
        manager.connection("crm").statement(
                "create table people (person_id integer primary key autoincrement, name text,"
                        + " created_at text, updated_at text)");
        connector.connects.set(0);
    }

    @Test
    void aModelNamingTheCrmConnectionSavesThroughTheManagersCrmConnection() {
        createTables();
        ConnectionResolver resolver = ConnectionResolver.of(manager);

        Person person = Person.QUERY.using(resolver).newModel();
        person.forceFill(row("name", "Ann"));
        person.save();

        assertEquals(1, manager.query("crm").table("people").count());
        assertSame(manager.query("crm"), person.getQueryFactory());
    }

    @Test
    void aModelWithoutAConnectionNameUsesTheManagersDefault() {
        createTables();
        ConnectionResolver resolver = ConnectionResolver.of(manager);

        User.QUERY.using(resolver).make(row("name", "Ann", "email", "a@x")).save();

        assertEquals(1, manager.query("main").table("users").count());
        assertEquals("main", resolver.getDefaultConnection());
    }

    @Test
    void theDefaultFollowsTheManager() {
        ConnectionResolver resolver = ConnectionResolver.of(manager);

        manager.setDefaultConnection("crm");

        assertEquals("crm", resolver.getDefaultConnection());
        assertSame(manager.query("crm"), resolver.resolve(null));
    }

    @Test
    void aConnectionIsBuiltOnlyWhenAModelReachesIt() {
        ConnectionResolver resolver = ConnectionResolver.of(manager);
        assertEquals(0, connector.connects.get());

        resolver.resolve("crm");
        resolver.resolve("crm");

        assertEquals(1, connector.connects.get());
    }

    @Test
    void registerAndSetDefaultConnectionAreRefusedNamingTheManager() {
        ConnectionResolver resolver = ConnectionResolver.of(manager);

        UnsupportedOperationException register = assertThrows(UnsupportedOperationException.class,
                () -> resolver.register("x", manager.query("main")));
        UnsupportedOperationException setDefault = assertThrows(UnsupportedOperationException.class,
                () -> resolver.setDefaultConnection("x"));

        assertTrue(register.getMessage().contains("DatabaseManager"), register.getMessage());
        assertTrue(setDefault.getMessage().contains("DatabaseManager"), setDefault.getMessage());
    }

    @Test
    void aNullManagerIsRefused() {
        assertThrows(NullPointerException.class, () -> ConnectionResolver.of(null));
    }
}
