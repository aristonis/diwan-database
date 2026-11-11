package org.aristonis.diwan.database.testing;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.aristonis.diwan.database.schema.CopyStrategy;
import org.aristonis.diwan.database.schema.DatabaseVersion;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.schema.grammar.SchemaQuery;

/**
 * Template mode on PostgreSQL: the template is a database, and each test's copy is created from it
 * with {@code create database ... template}.
 *
 * <p>Everything runs on the run's single root connection, so the two advisory locks of
 * {@link PostgresAdvisoryKeys} live as long as the run. The run lock tells other runs this one is
 * alive; the build lock lets one run at a time build a given template while the others wait and
 * then reuse it.
 *
 * <p>A template is built under a name only this run uses and then copied to its final name, so a
 * run that dies during the migrations leaves a build database, never a half-migrated template.
 * The sweep removes the copies and build databases of runs that are gone. It never removes a
 * finished template: a template whose hash differs from the current one goes only when the caller
 * asked for {@code dropStaleTemplates()}.
 *
 * <p>Calls after {@link #prepare} must be serialised by the caller, which
 * {@link TemplateDatabases} does.
 */
final class PostgresTemplateCopies implements TemplateCopies {

    private static final Logger LOGGER = Logger.getLogger(PostgresTemplateCopies.class.getName());

    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String OBJECT_IN_USE = "55006";
    private static final String DUPLICATE_DATABASE = "42P04";
    private static final int FORCE_SINCE = 13;

    /** How long a create or drop is retried while the server still counts a just-closed session. */
    private static final long BUSY_WAIT_MILLIS = 2000;
    private static final long BUSY_POLL_MILLIS = 50;

    // LIKE patterns with the default backslash escape, so the underscores match themselves.
    private static final String COPY_PATTERN = "diwan\\_t\\_%";
    private static final String BUILD_PATTERN = "diwan\\_tpl\\_%\\_b\\_%";
    private static final String TEMPLATE_PATTERN = "diwan\\_tpl\\_%";
    private static final Pattern FINAL_TEMPLATE = Pattern.compile("diwan_tpl_[0-9a-f]{8}");
    private static final String LIST_LEFTOVERS =
            "select datname from pg_database where datname like ? or datname like ?";
    private static final String LIST_TEMPLATES = "select datname from pg_database where datname like ?";

    private final TemplateName name;
    private final Connection connection;
    private final Schema schema;
    private final CopyStrategy explicitStrategy;
    private final boolean isDroppingStale;
    private final String runId = TemplateName.runId();
    private final Set<String> reportedDrops = new HashSet<>();

    private boolean isForcing;
    private CopyStrategy copyStrategy;
    private boolean isRunLocked;

    /**
     * Creates the copies of one run on PostgreSQL; sends no statement yet.
     *
     * @param name the names of the template and its copies, never {@code null}
     * @param run the run's root connection, never {@code null}
     * @param schema builds the schema builder of a connection, never {@code null}
     * @param strategy the copy strategy for every copy, or {@code null} for {@code FILE_COPY} where
     *     the server supports a strategy and the server's default elsewhere
     * @param isDroppingStale whether {@link #prepare} drops the templates of other migration lists
     * @throws NullPointerException if an argument other than {@code strategy} is {@code null}
     */
    PostgresTemplateCopies(TemplateName name, RunConnection run, Function<Connection, Schema> schema,
            CopyStrategy strategy, boolean isDroppingStale) {
        this.name = Objects.requireNonNull(name, "name");
        this.connection = Objects.requireNonNull(run, "run").connection();
        this.schema = Objects.requireNonNull(schema, "schema").apply(connection);
        this.explicitStrategy = strategy;
        this.isDroppingStale = isDroppingStale;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Takes the run lock first, so any database this run creates is already protected from other
     * runs' sweeps.
     *
     * @throws IllegalStateException if the role cannot create databases, or the build fails
     * @throws UnsupportedOperationException if an explicit strategy needs a newer server
     */
    @Override
    public void prepare(Function<String, DatabaseManager> perDatabase, Consumer<Connection> migrate) {
        String version = connection.scalar("show server_version").map(Object::toString)
                .orElseThrow(() -> new IllegalStateException("The server did not report server_version"));
        isForcing = DatabaseVersion.atLeast(version, FORCE_SINCE);
        copyStrategy = explicitStrategy != null ? explicitStrategy
                : schema.grammar().supportsCopyStrategy(connection) ? CopyStrategy.FILE_COPY : null;
        PostgresAdvisoryKeys.lock(connection, PostgresAdvisoryKeys.RUN_NS, TemplateName.runKey(runId));
        isRunLocked = true;
        buildTemplate(perDatabase, migrate);
        if (isDroppingStale) {
            dropStaleTemplates();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void sweep() {
        Map<String, Boolean> isGone = new HashMap<>();
        List<Map<String, Object>> rows = connection.select(LIST_LEFTOVERS, COPY_PATTERN, BUILD_PATTERN);
        for (Map<String, Object> row : rows) {
            String database = String.valueOf(row.get("datname"));
            Optional<String> owner = TemplateName.runIdOf(database);
            if (!owner.isPresent() || owner.get().equals(runId)) {
                continue;
            }
            if (isGone.computeIfAbsent(owner.get(), this::isRunGone)) {
                dropLeftover(database);
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if the role cannot create databases, or a session is open on the
     *     template and stays open
     * @throws UnsupportedOperationException if an explicit strategy needs a newer server
     */
    @Override
    public String createCopy(long n) {
        String copy = name.copy(runId, n);
        create(copy, name.template(), copyStrategy);
        return copy;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Closes the sessions still open on the copy where the server can ({@code with (force)},
     * PostgreSQL 13 and later); earlier servers retry for a moment while a just-closed session ends.
     */
    @Override
    public void dropCopy(String copy) {
        try {
            retryWhileBusy(() -> schema.dropDatabaseIfExists(copy, isForcing));
        } catch (QueryException e) {
            throw new IllegalStateException("Cannot drop the copy '" + copy + "': " + e.getMessage(), e);
        }
    }

    /** {@inheritDoc} Releases the run lock; the sweep of another run may then remove what this run left. */
    @Override
    public void close() {
        if (!isRunLocked) {
            return;
        }
        isRunLocked = false;
        try {
            PostgresAdvisoryKeys.unlock(connection, PostgresAdvisoryKeys.RUN_NS, TemplateName.runKey(runId));
        } catch (QueryException e) {
            // The session ends with the run anyway, which frees the lock; the failure is only reported.
            LOGGER.log(Level.WARNING, "Template mode could not release its run lock; it ends with the session", e);
        }
    }

    private void buildTemplate(Function<String, DatabaseManager> perDatabase, Consumer<Connection> migrate) {
        int hashKey = name.hash32();
        PostgresAdvisoryKeys.lock(connection, PostgresAdvisoryKeys.BUILD_NS, hashKey);
        try {
            if (!exists(name.template())) {
                buildAndPublish(perDatabase, migrate);
            }
        } finally {
            unlockBuild(hashKey);
        }
    }

    private void buildAndPublish(Function<String, DatabaseManager> perDatabase, Consumer<Connection> migrate) {
        String building = name.building(runId);
        // A failed earlier attempt of this same run may have left it; only this run uses the name.
        schema.dropDatabaseIfExists(building, isForcing);
        create(building, null, null);
        RuntimeException failure = null;
        try {
            TemplateCopies.migrateInto(perDatabase, building, migrate);
            publish(building);
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            dropBuilding(building, failure);
        }
    }

    private void publish(String building) {
        try {
            create(name.template(), building, null);
        } catch (QueryException e) {
            if (!hasState(e, DUPLICATE_DATABASE)) {
                throw e;
            }
            // Another run finished the same template between our exists check and this create.
        }
    }

    private void dropBuilding(String building, RuntimeException failure) {
        try {
            retryWhileBusy(() -> schema.dropDatabaseIfExists(building, isForcing));
        } catch (RuntimeException e) {
            if (failure != null) {
                failure.addSuppressed(e);
            } else {
                // The sweep of a later run removes it once this run is gone.
                LOGGER.log(Level.WARNING, "Template mode could not drop its build database '" + building + "'", e);
            }
        }
    }

    private void unlockBuild(int hashKey) {
        try {
            PostgresAdvisoryKeys.unlock(connection, PostgresAdvisoryKeys.BUILD_NS, hashKey);
        } catch (QueryException e) {
            LOGGER.log(Level.WARNING, "Template mode could not release its build lock; it ends with the session", e);
        }
    }

    private void dropStaleTemplates() {
        for (Map<String, Object> row : connection.select(LIST_TEMPLATES, TEMPLATE_PATTERN)) {
            String database = String.valueOf(row.get("datname"));
            if (!FINAL_TEMPLATE.matcher(database).matches() || database.equals(name.template())) {
                continue;
            }
            try {
                schema.dropDatabaseIfExists(database, false);
            } catch (QueryException e) {
                // Refused when another run is copying from it (55006): left for a later run.
                LOGGER.log(Level.WARNING, "Template mode could not drop the stale template '" + database + "'", e);
            }
        }
    }

    private boolean isRunGone(String owner) {
        int key = TemplateName.runKey(owner);
        if (!PostgresAdvisoryKeys.tryLock(connection, PostgresAdvisoryKeys.RUN_NS, key)) {
            return false;
        }
        PostgresAdvisoryKeys.unlock(connection, PostgresAdvisoryKeys.RUN_NS, key);
        return true;
    }

    private void dropLeftover(String database) {
        try {
            schema.dropDatabaseIfExists(database, isForcing);
        } catch (QueryException e) {
            if (reportedDrops.add(database)) {
                LOGGER.log(Level.WARNING, "Template mode could not drop the leftover database '" + database + "'", e);
            }
        }
    }

    private boolean exists(String database) {
        SchemaQuery query = schema.grammar().compileDatabaseExists(database);
        return !connection.select(query.sql(), query.bindings()).isEmpty();
    }

    private void create(String database, String template, CopyStrategy strategy) {
        try {
            retryWhileBusy(() -> {
                schema.createDatabase(database, template, strategy);
                return Boolean.TRUE;
            });
        } catch (QueryException e) {
            throw describe(e, template);
        }
    }

    private RuntimeException describe(QueryException failure, String template) {
        if (hasState(failure, INSUFFICIENT_PRIVILEGE)) {
            String role = connection.scalar("select current_user").map(Object::toString).orElse("the current role");
            return new IllegalStateException("Role '" + role + "' cannot create databases: grant it CREATEDB"
                    + " (ALTER ROLE " + role + " CREATEDB) to use template mode", failure);
        }
        if (template != null && hasState(failure, OBJECT_IN_USE)) {
            return new IllegalStateException("Cannot copy template " + template + ": a session on the template is"
                    + " open (source database is being accessed by other users). Close every connection to "
                    + template, failure);
        }
        return failure;
    }

    // The server counts a session until its backend has exited, a moment after the client closed it,
    // and copies made at once from one template can meet each other the same way. A session that
    // really stays open outlasts the wait and the failure reaches the caller.
    private static <T> T retryWhileBusy(Supplier<T> action) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUSY_WAIT_MILLIS);
        while (true) {
            try {
                return action.get();
            } catch (QueryException e) {
                if (!hasState(e, OBJECT_IN_USE) || System.nanoTime() - deadline >= 0) {
                    throw e;
                }
            }
            try {
                Thread.sleep(BUSY_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for a database to become free", e);
            }
        }
    }

    private static boolean hasState(QueryException failure, String state) {
        return failure.sqlState().filter(state::equals).isPresent();
    }
}
