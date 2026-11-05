package org.aristonis.diwan.database.manager;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.migrations.MigrationLock;
import org.aristonis.diwan.database.query.Processor;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.query.QueryGrammar;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.schema.SchemaGrammar;

/** A connector for the manager tests: counts what the manager asks of it and runs no SQL. */
public final class FakeConnector implements Connector {

    private final String driver;
    private final AtomicInteger connects = new AtomicInteger();
    private final List<Connection> closed = new CopyOnWriteArrayList<>();
    private final List<DataSource> sources = new CopyOnWriteArrayList<>();
    private volatile Consumer<ConnectionConfig> beforeConnect = config -> { };
    private volatile Function<ConnectionConfig, Connection> factory = FakeConnector::plainConnection;
    private volatile Optional<MigrationLock> lock = Optional.empty();
    private volatile ConnectionConfig lastConfig;

    /** Creates a connector for a driver name. */
    public FakeConnector(String driver) {
        this.driver = driver;
    }

    /** Runs a hook inside {@code connect}, such as one that waits on a latch. */
    public FakeConnector beforeConnect(Consumer<ConnectionConfig> hook) {
        this.beforeConnect = hook;
        return this;
    }

    /** Replaces how a connection is built, such as with a recording one. */
    public FakeConnector connectionFactory(Function<ConnectionConfig, Connection> newFactory) {
        this.factory = newFactory;
        return this;
    }

    /** Sets the lock {@code lock} reports. */
    public FakeConnector withLock(MigrationLock newLock) {
        this.lock = Optional.of(newLock);
        return this;
    }

    /** Returns how many times {@code connect} ran. */
    public int connects() {
        return connects.get();
    }

    /** Returns the connections passed to {@code close}, in order. */
    public List<Connection> closed() {
        return closed;
    }

    /** Returns the data sources {@code connect} received, with {@code null} replaced by nothing. */
    public List<DataSource> sources() {
        return sources;
    }

    /** Returns the config of the latest {@code connect}. */
    public ConnectionConfig lastConfig() {
        return lastConfig;
    }

    @Override
    public String driver() {
        return driver;
    }

    @Override
    public Connection connect(ConnectionConfig config, DataSource dataSource) {
        beforeConnect.accept(config);
        connects.incrementAndGet();
        lastConfig = config;
        if (dataSource != null) {
            sources.add(dataSource);
        }
        return factory.apply(config);
    }

    @Override
    public QueryFactory query(Connection connection) {
        return QueryFactory.of(connection, new QueryGrammar(connection.getTablePrefix()) { }, new Processor());
    }

    @Override
    public Schema schema(Connection connection) {
        return Schema.of(connection, new SchemaGrammar() { });
    }

    @Override
    public Optional<MigrationLock> lock(ConnectionConfig config, DataSource dataSource) {
        return lock;
    }

    @Override
    public void close(Connection connection) {
        closed.add(connection);
    }

    private static Connection plainConnection(ConnectionConfig config) {
        return Connection.builder()
                .name(config.name())
                .tablePrefix(config.prefix())
                .dataSource(new CountingDataSource())
                .build();
    }
}
