package org.aristonis.diwan.database.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.TransactionScope;
import org.aristonis.diwan.database.migrations.DatabaseMigrationRepository;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.migrations.Migrator;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.schema.TableInfo;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * A JUnit 5 extension that gives every test an empty database with the migrations applied.
 *
 * <p>The migrations run once per test run, through {@link Migrator#fresh()}, the first time a test
 * class that uses the extension starts. Later classes with the same connection instance and the same
 * migration list reuse that schema. The same connection with a different list in the same run is
 * refused with an {@link IllegalStateException}: migrating again would drop the tables of the
 * classes that already ran. The record lives in the root store of the JUnit run, so it is shared by
 * every class of the run. The migration list is compared by migration class and name, so editing the
 * body of a migration within one run is not noticed.
 *
 * <p>Two modes decide how a test's data is removed:
 * <ul>
 *   <li>{@link #rollback} opens a transaction before each test and rolls it back after. Calls the
 *       test makes through the root connection from its own thread run inside that transaction,
 *       even with strict transactions on, and {@code transaction(...)} becomes a savepoint.
 *       Callbacks of {@link Connection#afterCommit(Runnable)} registered inside never run, because
 *       nothing commits. Work done on other threads, such as threads or executors the test starts, or
 *       a test under {@code @Timeout(threadMode = SEPARATE_THREAD)}, is not part of the transaction
 *       and commits for real. Tests may run in parallel, one transaction per test thread.
 *   <li>{@link #truncate} lets the test commit for real and empties every table except the
 *       migrations table after it, in one {@link QueryFactory#truncate(List)} call, so identities
 *       restart. Slower, but afterCommit callbacks run and other threads see the data. Internal
 *       tables, such as PostGIS's {@code spatial_ref_sys}, are left alone, and a table counts only
 *       when its name starts with the table prefix of the connection. The list of tables is read
 *       once per run, after the first test, so a table a test creates later is not emptied. Tests
 *       must not run in parallel in this mode: it fails with an {@link IllegalStateException}
 *       under {@link ExecutionMode#CONCURRENT}; use rollback mode, or
 *       {@code @Execution(ExecutionMode.SAME_THREAD)}.
 * </ul>
 *
 * <p>Register it in a field of the test class, static or not:
 * <pre>{@code
 * @RegisterExtension
 * static RefreshDatabase db = RefreshDatabase.rollback(
 *         () -> SQLiteQuery.of(connection), SQLiteSchema::of, migrations);
 *
 * @Test
 * void savesAUser(DatabaseAssertions assertions, QueryFactory query) {
 *     query.table("users").insert(Map.of("name", "ann"));
 *     assertions.assertCount("users", 1);
 * }
 * }</pre>
 *
 * <p>Test methods may declare parameters of type {@link DatabaseAssertions}, {@link QueryFactory}
 * and {@link Connection}; the extension resolves them from the supplied query factory.
 *
 * <p>The supplier must return the same connection instance every time, which identifies the
 * database. Instances are safe to share between tests; the suppliers and the function must be safe
 * to call from the test thread.
 *
 * <p>Requirements: JUnit 5.10 or later (the extension was not run against 5.x). The eloquent module
 * is needed only by the model assertions. {@code dropAllTables} on PostgreSQL drops tables, not
 * types, sequences or functions, so a migration that creates one drops it itself, or the schema of
 * the tests is a dedicated one.
 */
public final class RefreshDatabase
        implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final Namespace NAMESPACE = Namespace.create(RefreshDatabase.class);

    /** Serialises the check-then-migrate step, so two classes running in parallel migrate once. */
    private static final Object MIGRATION_LOCK = new Object();

    private static final String SCOPE = "scope";
    private static final String READY = "ready";
    private static final String MIGRATED = "migrated";
    private static final String TABLES = "tables";

    private enum Mode {
        ROLLBACK,
        TRUNCATE
    }

    private final Mode mode;
    private final Supplier<QueryFactory> db;
    private final Function<Connection, Schema> schema;
    private final List<Migration> migrations;

    /** Per-instance state of one test: the open scope, and whether its set-up finished. */
    private final Namespace own = Namespace.create(RefreshDatabase.class, this);

    private RefreshDatabase(Mode mode, Supplier<QueryFactory> db, Function<Connection, Schema> schema,
            List<Migration> migrations) {
        this.mode = mode;
        this.db = Objects.requireNonNull(db, "db");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.migrations = new ArrayList<>(Objects.requireNonNull(migrations, "migrations"));
        for (Migration migration : this.migrations) {
            Objects.requireNonNull(migration, "A migration is null");
        }
    }

    /**
     * Creates an extension that rolls every test back.
     *
     * @param db supplies the query factory of the root connection, never {@code null}; called before
     *     each test, so it may create the connection lazily but must return the same database
     * @param schema builds the schema builder for the connection of {@code db}, never {@code null}
     * @param migrations the migrations that build the test schema, never {@code null}, without
     *     {@code null} elements; copied
     * @return the extension, never {@code null}
     * @throws NullPointerException if an argument or an element is {@code null}
     */
    public static RefreshDatabase rollback(Supplier<QueryFactory> db, Function<Connection, Schema> schema,
            List<Migration> migrations) {
        return new RefreshDatabase(Mode.ROLLBACK, db, schema, migrations);
    }

    /**
     * Creates an extension that empties the tables after every test.
     *
     * @param db supplies the query factory of the root connection, never {@code null}; called before
     *     and after each test
     * @param schema builds the schema builder for the connection of {@code db}, never {@code null}
     * @param migrations the migrations that build the test schema, never {@code null}, without
     *     {@code null} elements; copied
     * @return the extension, never {@code null}
     * @throws NullPointerException if an argument or an element is {@code null}
     */
    public static RefreshDatabase truncate(Supplier<QueryFactory> db, Function<Connection, Schema> schema,
            List<Migration> migrations) {
        return new RefreshDatabase(Mode.TRUNCATE, db, schema, migrations);
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        refuseParallelTruncate(context);
        migrateOnce(context);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        refuseParallelTruncate(context);
        // A non-static registration never gets beforeAll; the check is cheap once migrated.
        migrateOnce(context);
        ExtensionContext.Store store = context.getStore(own);
        if (mode == Mode.ROLLBACK) {
            store.put(SCOPE, db.get().connection().beginJoinedTransaction());
        }
        store.put(READY, Boolean.TRUE);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(own);
        boolean isReady = store.remove(READY, Boolean.class) != null;
        if (mode == Mode.ROLLBACK) {
            TransactionScope scope = store.remove(SCOPE, TransactionScope.class);
            if (scope != null) {
                scope.close();
            }
            return;
        }
        if (!isReady) {
            // The set-up failed: leave the database alone, so its error is the one that is reported.
            return;
        }
        QueryFactory factory = db.get();
        factory.truncate(tablesToEmpty(context, factory.connection()));
    }

    private void refuseParallelTruncate(ExtensionContext context) {
        if (mode == Mode.TRUNCATE && isRunningInParallel(context)) {
            throw new IllegalStateException("Truncate mode cannot run tests in parallel: one test would empty the"
                    + " tables under another. Use rollback mode, or @Execution(ExecutionMode.SAME_THREAD)");
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        Class<?> type = parameter.getParameter().getType();
        return type == DatabaseAssertions.class || type == QueryFactory.class || type == Connection.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        Class<?> type = parameter.getParameter().getType();
        QueryFactory factory = db.get();
        if (type == DatabaseAssertions.class) {
            return DatabaseAssertions.of(factory);
        }
        if (type == QueryFactory.class) {
            return factory;
        }
        if (type == Connection.class) {
            return factory.connection();
        }
        throw new ParameterResolutionException("Cannot resolve a parameter of type " + type.getName());
    }

    // JUnit reports CONCURRENT as soon as it is the configured default mode, even with parallel
    // execution switched off, so the switch is checked too.
    private static boolean isRunningInParallel(ExtensionContext context) {
        boolean isEnabled = context.getConfigurationParameter("junit.jupiter.execution.parallel.enabled")
                .map(Boolean::parseBoolean).orElse(false);
        return isEnabled && context.getExecutionMode() == ExecutionMode.CONCURRENT;
    }

    private List<String> tablesToEmpty(ExtensionContext context, Connection connection) {
        // get and put under a lock, not getOrComputeIfAbsent: that is deprecated in JUnit 6 and its
        // replacement is missing from the JUnit 5.10 this module supports.
        ExtensionContext.Store store = context.getRoot().getStore(NAMESPACE);
        RunKey key = new RunKey(connection, TABLES);
        synchronized (MIGRATION_LOCK) {
            TableList cached = store.get(key, TableList.class);
            if (cached == null) {
                cached = new TableList(listTables(connection));
                store.put(key, cached);
            }
            return cached.tables;
        }
    }

    /**
     * Lists the tables to empty as the truncate call wants them: without the connection prefix, which
     * it adds, and qualified with the schema unless that is the default one.
     */
    private List<String> listTables(Connection connection) {
        Schema builder = schema.apply(connection);
        String keep = new DatabaseMigrationRepository(builder).table();
        String defaultSchema = defaultSchema(builder, connection);
        String prefix = connection.getTablePrefix();
        List<String> tables = new ArrayList<>();
        for (TableInfo table : builder.getTables()) {
            boolean isDefault = table.schema().equals(defaultSchema);
            boolean isOurs = table.name().length() > prefix.length() && table.name().startsWith(prefix);
            if (!isOurs || builder.grammar().isInternalTable(table) || (isDefault && table.name().equals(keep))) {
                continue;
            }
            String bare = table.name().substring(prefix.length());
            tables.add(isDefault ? bare : table.schema() + "." + bare);
        }
        return tables;
    }

    private static String defaultSchema(Schema builder, Connection connection) {
        if ("pgsql".equals(builder.driverName())) {
            return String.valueOf(connection.scalar("select current_schema()").orElse("public"));
        }
        return "main";
    }

    private void migrateOnce(ExtensionContext context) {
        Connection connection = db.get().connection();
        String signature = signature();
        RunKey key = new RunKey(connection, MIGRATED);
        ExtensionContext.Store store = context.getRoot().getStore(NAMESPACE);
        synchronized (MIGRATION_LOCK) {
            String known = store.get(key, String.class);
            if (signature.equals(known)) {
                return;
            }
            if (known != null) {
                throw new IllegalStateException("Connection '" + connection.getName() + "' was already migrated in"
                        + " this run with a different migration list; migrating again would drop the tables of the"
                        + " classes that ran before. Use one migration list per database in a run");
            }
            Migrator.builder(schema.apply(connection)).migrations(migrations).build().fresh();
            store.put(key, signature);
        }
    }

    private String signature() {
        StringBuilder signature = new StringBuilder();
        for (Migration migration : migrations) {
            signature.append(migration.getClass().getName()).append('#').append(migration.name()).append(';');
        }
        return signature.toString();
    }

    /** A root-store key: one connection instance, by identity, and what is recorded for it. */
    private static final class RunKey {
        private final Connection connection;
        private final String kind;

        RunKey(Connection connection, String kind) {
            this.connection = connection;
            this.kind = kind;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RunKey && ((RunKey) other).connection == connection
                    && ((RunKey) other).kind.equals(kind);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(connection) * 31 + kind.hashCode();
        }
    }

    /** The tables to empty, kept in the root store. */
    private static final class TableList {
        private final List<String> tables;

        TableList(List<String> tables) {
            this.tables = tables;
        }
    }
}
