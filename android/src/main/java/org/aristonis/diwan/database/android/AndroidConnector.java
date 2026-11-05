package org.aristonis.diwan.database.android;

import android.database.sqlite.SQLiteOpenHelper;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.manager.ConnectionConfig;
import org.aristonis.diwan.database.manager.Connector;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.schema.Schema;

/**
 * The {@link Connector} for Android's SQLite, driver name {@code android}.
 *
 * <p>An Android database needs a {@code Context}, which no configuration file can hold, so the
 * connector is built in code from a supplier of the application's {@link SQLiteOpenHelper}. The
 * supplier runs when the connection is first built, not before, because opening the database runs
 * the helper's {@code onCreate} or {@code onUpgrade}; build the first connection off the main
 * thread. The {@code url}, {@code host} and {@code database} settings are ignored, and so is a
 * supplied {@link DataSource}; the helper decides where the database lives.
 *
 * <p>Queries and schema use the SQLite grammars, which {@link #of(Supplier, Function, Function)}
 * takes explicitly: {@code AndroidConnector.of(() -> helper, SQLiteQuery::of, SQLiteSchema::of)}.
 *
 * <p>Instances are safe to share across threads and across connection names.
 */
public final class AndroidConnector implements Connector {

    /** The driver name this connector answers to. */
    public static final String DRIVER = "android";

    private final Supplier<? extends SQLiteOpenHelper> helper;
    private final Function<Connection, QueryFactory> queryFactory;
    private final Function<Connection, Schema> schemaFactory;

    private AndroidConnector(Supplier<? extends SQLiteOpenHelper> helper,
            Function<Connection, QueryFactory> queryFactory, Function<Connection, Schema> schemaFactory) {
        this.helper = Objects.requireNonNull(helper, "helper");
        this.queryFactory = Objects.requireNonNull(queryFactory, "queryFactory");
        this.schemaFactory = Objects.requireNonNull(schemaFactory, "schemaFactory");
    }

    /**
     * Creates a connector with explicit query and schema factories, for example
     * {@code AndroidConnector.of(() -> helper, SQLiteQuery::of, SQLiteSchema::of)}.
     *
     * @param helper the supplier of the application's helper, never {@code null}; called once per
     *     connection built, off the main thread
     * @param queryFactory builds the query factory of a connection, for example
     *     {@code SQLiteQuery::of}, never {@code null}
     * @param schemaFactory builds the schema of a connection, for example {@code SQLiteSchema::of},
     *     never {@code null}
     * @return the connector, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public static AndroidConnector of(Supplier<? extends SQLiteOpenHelper> helper,
            Function<Connection, QueryFactory> queryFactory, Function<Connection, Schema> schemaFactory) {
        return new AndroidConnector(helper, queryFactory, schemaFactory);
    }

    @Override
    public String driver() {
        return DRIVER;
    }

    /**
     * {@inheritDoc}
     *
     * @throws NullPointerException if the helper supplier returns {@code null}
     */
    @Override
    public Connection connect(ConnectionConfig config, DataSource dataSource) {
        SQLiteOpenHelper opened = Objects.requireNonNull(helper.get(), "helper supplier returned null");
        return Connection.builder().name(config.name()).driver(AndroidSqliteDriver.of(opened))
                .tablePrefix(config.prefix()).strictTransactions(config.strictTransactions()).build();
    }

    @Override
    public QueryFactory query(Connection connection) {
        return queryFactory.apply(connection);
    }

    @Override
    public Schema schema(Connection connection) {
        return schemaFactory.apply(connection);
    }
}
