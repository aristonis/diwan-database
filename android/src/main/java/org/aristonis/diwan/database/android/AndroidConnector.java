package org.aristonis.diwan.database.android;

import android.database.sqlite.SQLiteOpenHelper;
import java.io.File;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.config.LockWaitOption;
import org.aristonis.diwan.database.exception.DatabaseConfigException;
import org.aristonis.diwan.database.manager.ConnectionConfig;
import org.aristonis.diwan.database.manager.Connector;
import org.aristonis.diwan.database.migrations.FileMigrationLock;
import org.aristonis.diwan.database.migrations.MigrationLock;
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
 * <p>The {@code transaction_mode} option chooses how a transaction begins. Unset or {@code exclusive}
 * (any case) uses {@code beginTransaction()}, as before; {@code immediate} uses
 * {@code beginTransactionNonExclusive()}, which takes the write lock at the start. {@code deferred} is
 * refused, because Android cannot begin a deferred transaction, and so is any other value. In
 * write-ahead-logging mode immediate and exclusive behave alike, since readers do not block the writer.
 *
 * <p>The {@code lock_wait} option, such as {@code 5s} or {@code 500ms}, sets {@code PRAGMA busy_timeout}
 * once the database opens, so every statement waits that long for a lock before it fails.
 *
 * <p>A migrator run is serialised by a {@link FileMigrationLock} on a file beside the database, named
 * from the helper's database file with {@code .migrate.lock} added; the lock waits as long as
 * {@code lock_wait}, or {@value FileMigrationLock#DEFAULT_WAIT_SECONDS} seconds when it is unset. A helper
 * with no database name is in memory and has no lock. The lock opens the database when a run starts, so
 * run migrations off the main thread. Opening runs the helper's own {@code onCreate} or
 * {@code onUpgrade} before the lock is held, so keep schema changes in migrations, not in the helper.
 *
 * <p>Queries and schema use the SQLite grammars, which {@link #of(Supplier, Function, Function)}
 * takes explicitly: {@code AndroidConnector.of(() -> helper, SQLiteQuery::of, SQLiteSchema::of)}.
 *
 * <p>Instances are safe to share across threads and across connection names.
 */
public final class AndroidConnector implements Connector {

    /** The driver name this connector answers to. */
    public static final String DRIVER = "android";

    private static final String TRANSACTION_MODE = "transaction_mode";

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
     * @throws DatabaseConfigException if {@code transaction_mode} is {@code deferred} or is not
     *     {@code immediate} or {@code exclusive}, or if {@code lock_wait} is not a positive duration
     */
    @Override
    public Connection connect(ConnectionConfig config, DataSource dataSource) {
        boolean isNonExclusive = isNonExclusive(config);
        long busyTimeout = busyTimeout(config);
        SQLiteOpenHelper opened = Objects.requireNonNull(helper.get(), "helper supplier returned null");
        return Connection.builder().name(config.name()).driver(AndroidSqliteDriver.of(opened, isNonExclusive, busyTimeout))
                .tablePrefix(config.prefix()).strictTransactions(config.strictTransactions()).build();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The helper's database file is not opened here; the lock opens it when it is acquired.
     *
     * @throws NullPointerException if the helper supplier returns {@code null}
     * @throws DatabaseConfigException if {@code lock_wait} is not a positive duration
     */
    @Override
    public Optional<MigrationLock> lock(ConnectionConfig config, DataSource dataSource) {
        long waitMillis = busyTimeout(config);
        SQLiteOpenHelper opened = Objects.requireNonNull(helper.get(), "helper supplier returned null");
        if (opened.getDatabaseName() == null) {
            return Optional.empty();
        }
        long wait = waitMillis == 0L ? FileMigrationLock.DEFAULT_WAIT_SECONDS : waitMillis;
        TimeUnit unit = waitMillis == 0L ? TimeUnit.SECONDS : TimeUnit.MILLISECONDS;
        return Optional.of(new FileMigrationLock(() -> new File(opened.getReadableDatabase().getPath()), wait, unit));
    }

    // Zero means the option is unset and the database keeps its own busy timeout.
    private static long busyTimeout(ConnectionConfig config) {
        String raw = config.options().get(LockWaitOption.KEY);
        return raw == null ? 0L : LockWaitOption.parseMillis(config.name(), LockWaitOption.KEY, raw);
    }

    private static boolean isNonExclusive(ConnectionConfig config) {
        String raw = config.options().get(TRANSACTION_MODE);
        if (raw == null) {
            return false;
        }
        String mode = raw.trim().toLowerCase(Locale.ROOT);
        if (mode.equals("exclusive")) {
            return false;
        }
        if (mode.equals("immediate")) {
            return true;
        }
        String reason = mode.equals("deferred")
                ? "Android cannot begin a deferred transaction; use immediate or exclusive"
                : "must be immediate or exclusive, got [" + raw + "]";
        throw new DatabaseConfigException("Connection [" + config.name() + "] option " + TRANSACTION_MODE + ": "
                + reason, config.name(), TRANSACTION_MODE);
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
