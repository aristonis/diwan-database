package org.aristonis.diwan.database.testing;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.TransactionScope;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.aristonis.diwan.database.migrations.DatabaseMigrationRepository;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.migrations.Migrator;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.schema.CopyStrategy;
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
 * <p>Three modes decide how a test's data is removed:
 * <ul>
 *   <li>{@link #rollback} opens a transaction before each test and rolls it back after. Calls the
 *       test makes through the root connection from its own thread run inside that transaction,
 *       even with strict transactions on, and {@code transaction(...)} becomes a savepoint.
 *       Callbacks of {@link Connection#afterCommit(Runnable)} registered inside never run, because
 *       nothing commits. Work done on other threads, such as threads or executors the test starts, or
 *       a test under {@code @Timeout(threadMode = SEPARATE_THREAD)}, is not part of the transaction
 *       and commits for real. Tests may run in parallel, one transaction per test thread.
 *       Sequences are not rolled back, so on PostgreSQL the ids a test gets depend on the tests
 *       that ran before it. {@link #restartingIdentities()} sets them back before each test, to the
 *       value after the highest committed id: rows a migration or a seeder committed are kept, so
 *       the first new row gets the id after them, and the same ids every time. It needs
 *       PostgreSQL 10 or later, does nothing on SQLite, whose rollback already restores its
 *       counters, and refuses parallel tests, which share the sequences; Gradle forks that use
 *       one database are not detected, so give each fork its own database.
 *   <li>{@link #truncate} lets the test commit for real and empties every table except the
 *       migrations table after it, in one {@link QueryFactory#truncate(List)} call, so identities
 *       restart. Slower, but afterCommit callbacks run and other threads see the data. Internal
 *       tables, such as PostGIS's {@code spatial_ref_sys}, are left alone, and a table counts only
 *       when its name starts with the table prefix of the connection. The list of tables is read
 *       once per run, after the first test, so a table a test creates later is not emptied. Tests
 *       must not run in parallel in this mode: it fails with an {@link IllegalStateException}
 *       under {@link ExecutionMode#CONCURRENT}; use rollback mode, or
 *       {@code @Execution(ExecutionMode.SAME_THREAD)}.
 *   <li>{@link #template(DataSource, Function, Function, List)} gives every test its own database,
 *       a copy of a template that holds the migrated schema. The migrations run once per migration list, into a template database that
 *       later runs reuse; each test copies it, and the copy is dropped after the test. The test
 *       may commit, use other threads and run in parallel, and sequences start over. It needs
 *       PostgreSQL, where the role can create databases, or SQLite with {@link #directory(Path)};
 *       an in-memory SQLite database is refused. The extension holds one connection on the root data
 *       source for the whole run, so give it a plain driver data source or a pool in session mode.
 *       {@link #strategy(CopyStrategy)}, {@link #directory(Path)} and
 *       {@link #dropStaleTemplates()} tune it. The parameters below come from the test's own
 *       database.
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

    static final Namespace NAMESPACE = Namespace.create(RefreshDatabase.class);

    /** Serialises the check-then-migrate step, so two classes running in parallel migrate once. */
    static final Object MIGRATION_LOCK = new Object();

    private static final String SCOPE = "scope";
    private static final String READY = "ready";
    private static final String MIGRATED = "migrated";
    private static final String TABLES = "tables";

    private enum Mode {
        ROLLBACK,
        TRUNCATE,
        TEMPLATE
    }

    private final Mode mode;
    private final boolean isRestartingIdentities;
    private final Supplier<QueryFactory> db;
    private final Function<Connection, Schema> schema;
    private final List<Migration> migrations;
    private final TemplateSettings template;

    /** Per-instance state of one test: the open scope, and whether its set-up finished. */
    private final Namespace own = Namespace.create(RefreshDatabase.class, this);

    private RefreshDatabase(Mode mode, boolean isRestartingIdentities, Supplier<QueryFactory> db,
            Function<Connection, Schema> schema, List<Migration> migrations, TemplateSettings template) {
        this.mode = mode;
        this.isRestartingIdentities = isRestartingIdentities;
        this.template = template;
        this.db = mode == Mode.TEMPLATE ? db : Objects.requireNonNull(db, "db");
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
        return new RefreshDatabase(Mode.ROLLBACK, false, db, schema, migrations, null);
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
        return new RefreshDatabase(Mode.TRUNCATE, false, db, schema, migrations, null);
    }

    /**
     * Creates an extension that gives every test its own copy of a migrated template database.
     *
     * <p>The template is named after the migration list, class, name and checksum of each
     * migration in order, so a run with the same list reuses the template an earlier run built, and
     * a changed list builds a new one; older templates stay unless {@link #dropStaleTemplates()} is
     * set. Runs that start together, in this or in other JVMs, build it once. Copies and build
     * databases that a run which died left behind are removed at the start of the next run.
     *
     * @param root the data source of the server that holds the databases, never {@code null}; the
     *     extension takes one connection from it and holds it until the run ends
     * @param perDatabase opens a manager on the database of the given name, never {@code null}; the
     *     extension closes it after each test and after migrating the template. On SQLite the name is
     *     the path of the file to open. It must not keep a session open on the template
     * @param schema builds the schema builder for a connection, never {@code null}
     * @param migrations the migrations that build the test schema, never {@code null}, without
     *     {@code null} elements; copied
     * @return the extension, never {@code null}
     * @throws NullPointerException if an argument or an element is {@code null}
     */
    public static RefreshDatabase template(DataSource root, Function<String, DatabaseManager> perDatabase,
            Function<Connection, Schema> schema, List<Migration> migrations) {
        TemplateSettings settings = TemplateSettings.of(root, perDatabase, schema, migrations);
        return new RefreshDatabase(Mode.TEMPLATE, false, null, schema, migrations, settings);
    }

    /**
     * Returns an extension like this one that sends a copy strategy when it creates each copy.
     *
     * <p>Without it, PostgreSQL 15 or later gets {@code FILE_COPY} and older servers get none. An
     * explicit strategy on a server older than 15 fails with an
     * {@link UnsupportedOperationException} when the first copy is made.
     *
     * @param strategy the strategy, never {@code null}
     * @return a new extension with the option set; this one is not changed, never {@code null}
     * @throws NullPointerException if {@code strategy} is {@code null}
     * @throws IllegalStateException if this extension is not in template mode
     */
    public RefreshDatabase strategy(CopyStrategy strategy) {
        Objects.requireNonNull(strategy, "strategy");
        return withTemplate("strategy", template == null ? null : template.withStrategy(strategy));
    }

    /**
     * Returns an extension like this one that keeps its SQLite template and copies as files in a
     * directory.
     *
     * @param directory the directory of the template and the copies, never {@code null}; the name
     *     {@code perDatabase} receives is the path of a file in it
     * @return a new extension with the option set; this one is not changed, never {@code null}
     * @throws NullPointerException if {@code directory} is {@code null}
     * @throws IllegalStateException if this extension is not in template mode
     */
    public RefreshDatabase directory(Path directory) {
        Objects.requireNonNull(directory, "directory");
        return withTemplate("directory", template == null ? null : template.withDirectory(directory));
    }

    /**
     * Returns an extension like this one that also drops the templates of other migration lists at
     * the start of a run.
     *
     * <p>Without it older templates stay on the server or in the directory. On PostgreSQL a template
     * that another live run is copying from cannot be dropped; the refusal is logged and the run goes
     * on. On SQLite the other templates' files are deleted with their side and lock files, except a
     * template whose build lock another holder has.
     *
     * @return a new extension with the option set; this one is not changed, never {@code null}
     * @throws IllegalStateException if this extension is not in template mode
     */
    public RefreshDatabase dropStaleTemplates() {
        return withTemplate("dropStaleTemplates", template == null ? null : template.withDroppingStale());
    }

    /** Swaps the engine-specific copies of template mode; tests run the extension with a fake engine. */
    RefreshDatabase withCopies(TemplateCopies.Factory copies) {
        return withTemplate("withCopies", template == null ? null : template.withCopies(copies));
    }

    private RefreshDatabase withTemplate(String option, TemplateSettings changed) {
        if (mode != Mode.TEMPLATE) {
            throw new IllegalStateException(option + "() applies to template mode");
        }
        return new RefreshDatabase(mode, false, db, schema, migrations, changed);
    }

    /**
     * Returns an extension like this one that also sets the identity and serial counters back
     * before each test, so the test sees the ids it would see on a freshly migrated database.
     *
     * <p>Only rollback mode needs it: a rollback leaves the PostgreSQL sequences where the test
     * moved them, while truncate mode restarts them already. Before each test, after the migrations
     * ran and before the test's transaction opens, the counters of the tables the extension owns
     * are set to the value after the highest committed id, or to the start value of an empty
     * table; see {@link QueryFactory#restartIdentities(List)}. The table list is read once per run,
     * right after the migrations, so a table a test creates later is not restarted. This needs
     * PostgreSQL 10 or later and does nothing on SQLite. Tests must not run in parallel with this
     * option: they share the sequences, and the extension fails with an
     * {@link IllegalStateException} under {@link ExecutionMode#CONCURRENT}. The check sees one
     * JVM, so Gradle forks that use one database are not caught.
     *
     * @return a new extension with the option on; this one is not changed, never {@code null}
     * @throws IllegalStateException if this extension is in truncate mode, which restarts identities
     *     already, or in template mode, where every test starts on a fresh copy
     */
    public RefreshDatabase restartingIdentities() {
        if (mode == Mode.TRUNCATE) {
            throw new IllegalStateException("Truncate mode already restarts identities");
        }
        if (mode == Mode.TEMPLATE) {
            throw new IllegalStateException("Template mode starts every test on a fresh copy");
        }
        return new RefreshDatabase(mode, true, db, schema, migrations, null);
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        if (mode == Mode.TEMPLATE) {
            TemplateDatabases.beforeAll(context, template);
            return;
        }
        refuseParallel(context);
        migrateOnce(context);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        if (mode == Mode.TEMPLATE) {
            TemplateDatabases.beforeEach(context, context.getStore(own), template);
            return;
        }
        refuseParallel(context);
        // A non-static registration never gets beforeAll; the check is cheap once migrated.
        migrateOnce(context);
        ExtensionContext.Store store = context.getStore(own);
        if (mode == Mode.ROLLBACK) {
            QueryFactory factory = db.get();
            if (isRestartingIdentities) {
                // Before the transaction opens, so a failure leaves no scope behind.
                factory.restartIdentities(ownTables(context, factory.connection()));
            }
            store.put(SCOPE, factory.connection().beginJoinedTransaction());
        }
        store.put(READY, Boolean.TRUE);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        if (mode == Mode.TEMPLATE) {
            TemplateDatabases.afterEach(context.getStore(own));
            return;
        }
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
        factory.truncate(ownTables(context, factory.connection()));
    }

    private void refuseParallel(ExtensionContext context) {
        if (!isRunningInParallel(context)) {
            return;
        }
        if (mode == Mode.TRUNCATE) {
            throw new IllegalStateException("Truncate mode cannot run tests in parallel: one test would empty the"
                    + " tables under another. Use rollback mode, or @Execution(ExecutionMode.SAME_THREAD)");
        }
        if (isRestartingIdentities) {
            throw new IllegalStateException("restartingIdentities() cannot run tests in parallel: tests share the"
                    + " sequences. Use @Execution(ExecutionMode.SAME_THREAD)");
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
        QueryFactory factory = mode == Mode.TEMPLATE ? TemplateDatabases.queryOf(context.getStore(own)) : db.get();
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

    private List<String> ownTables(ExtensionContext context, Connection connection) {
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
        String signature = TemplateName.signature(migrations);
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
            migrate(schema, migrations, connection);
            store.put(key, signature);
        }
    }

    /** Runs every migration from scratch on the database behind a connection. */
    static void migrate(Function<Connection, Schema> schema, List<Migration> migrations, Connection connection) {
        Migrator.builder(schema.apply(connection)).migrations(migrations).build().fresh();
    }

    /** The tables to empty, kept in the root store. */
    private static final class TableList {
        private final List<String> tables;

        TableList(List<String> tables) {
            this.tables = tables;
        }
    }
}
