package org.aristonis.diwan.database.migrations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.LoggedQuery;
import org.aristonis.diwan.database.TransactionCallback;
import org.aristonis.diwan.database.driver.DriverCapability;
import org.aristonis.diwan.database.migrations.MigrationFailedException.Direction;
import org.aristonis.diwan.database.migrations.MigrationFailedException.Phase;
import org.aristonis.diwan.database.migrations.MigrationResult.Outcome;
import org.aristonis.diwan.database.schema.ForeignKeysOffRequiredException;
import org.aristonis.diwan.database.schema.Schema;

/**
 * Runs migrations and rolls them back, as Laravel's migrator does.
 *
 * <pre>{@code
 * Migrator migrator = Migrator.builder(SQLiteSchema.of(db))
 *         .add(new CreateUsersTable())
 *         .add(new AddBioToUsers())
 *         .build();
 * migrator.run();
 * }</pre>
 *
 * <p>Pending migrations run in name order. Each one runs in its own transaction when it asks for one
 * and the database can undo schema changes; when it runs on the connection that holds the
 * migrations table, its record is written in that same transaction, so the change and its record
 * are kept or undone together. Otherwise the record is written right after the migration; a crash
 * in between leaves the change applied and unrecorded, as in Laravel.
 *
 * <p>A failure stops the call with a {@link MigrationFailedException}; migrations before the failed
 * one stay applied and recorded. A rollback or reset that reaches records whose migrations this
 * migrator does not have is refused before anything is undone ({@link MissingMigrationsException}).
 *
 * <p>Every call refuses to start while one of the migrator's connections is inside a
 * transaction, because nothing it recorded would be final until that transaction ends. Only one
 * process should migrate a database at a time; plug a {@link MigrationLock} into the builder to
 * enforce that.
 *
 * <p>Instances are immutable. Calls on one migrator are not meant to overlap.
 */
public final class Migrator {

    private final Schema defaultSchema;
    private final Map<String, Schema> connections;
    private final List<Migration> migrations;
    private final Map<String, Migration> byName;
    private final String table;
    private final DatabaseMigrationRepository repository;
    private final MigrationLock lock;
    private final boolean isMissingAllowed;
    private final MigrationNotifier notifier;

    private Migrator(Builder builder, Map<String, Migration> named) {
        this.defaultSchema = builder.defaultSchema;
        this.connections = Collections.unmodifiableMap(new LinkedHashMap<>(builder.connections));
        List<String> names = new ArrayList<>(named.keySet());
        Collections.sort(names);
        Map<String, Migration> sorted = new LinkedHashMap<>();
        for (String name : names) {
            sorted.put(name, named.get(name));
        }
        this.byName = Collections.unmodifiableMap(sorted);
        this.migrations = Collections.unmodifiableList(new ArrayList<>(sorted.values()));
        this.table = builder.table;
        this.repository = new DatabaseMigrationRepository(defaultSchema, table);
        this.lock = builder.lock;
        this.isMissingAllowed = builder.isMissingAllowed;
        this.notifier = new MigrationNotifier(builder.listeners, defaultSchema.connection().getName());
    }

    /**
     * Starts building a migrator.
     *
     * @param defaultSchema the schema builder of the default connection, never {@code null}; it
     *     holds the migrations table and runs every migration that names no connection
     * @return the builder, never {@code null}
     * @throws NullPointerException if {@code defaultSchema} is {@code null}
     */
    public static Builder builder(Schema defaultSchema) {
        return new Builder(Objects.requireNonNull(defaultSchema, "defaultSchema"));
    }

    /**
     * Returns the repository of the migrations table on the default connection.
     *
     * @return the repository, never {@code null}
     */
    public DatabaseMigrationRepository repository() {
        return repository;
    }

    /**
     * Returns the migrations, in the order they run.
     *
     * @return an unmodifiable list sorted by name, never {@code null}
     */
    public List<Migration> migrations() {
        return migrations;
    }

    /**
     * Runs every pending migration in one new batch, creating the migrations table if needed.
     *
     * @return one result per pending migration, in order; empty when nothing is pending
     * @throws IllegalStateException if a connection is inside a transaction
     * @throws MigrationChecksumException if a migration that ran declares a different checksum than
     *     the one recorded; nothing runs then
     * @throws MigrationFailedException if a migration fails
     * @throws org.aristonis.diwan.database.QueryException if reading or creating the migrations
     *     table fails
     */
    public List<MigrationResult> run() {
        return run(false);
    }

    /**
     * Runs every pending migration, creating the migrations table if needed.
     *
     * @param step {@code true} to give each migration its own batch, skipped ones included, so
     *     each can be rolled back alone
     * @return one result per pending migration, in order; empty when nothing is pending
     * @throws IllegalStateException if a connection is inside a transaction
     * @throws MigrationChecksumException if a migration that ran declares a different checksum than
     *     the one recorded; nothing runs then
     * @throws MigrationFailedException if a migration fails
     * @throws org.aristonis.diwan.database.QueryException if reading or creating the migrations
     *     table fails
     */
    public List<MigrationResult> run(boolean step) {
        ensureNoOpenTransaction();
        return locked(() -> runPending(step));
    }

    private List<MigrationResult> runPending(boolean step) {
        if (!repository.repositoryExists()) {
            repository.createRepository();
        } else {
            repository.requireChecksumColumn();
        }
        Map<String, Optional<String>> stored = repository.checksums();
        MigrationChecksums.validate(stored, migrations);
        MigrationChecksums.adopt(repository, stored, migrations);
        List<String> pending = pending(new HashSet<>(repository.getRan()));
        List<MigrationResult> results = new ArrayList<>();
        MigrationsEvent whole = notifier.whole(Direction.UP, false);
        if (pending.isEmpty()) {
            return nothingPending(whole);
        }
        notifier.migrationsStarted(whole);
        int batch = repository.getNextBatchNumber();
        for (String name : pending) {
            Migration migration = byName.get(name);
            if (migration.shouldRun()) {
                MigrationEvent event = event(name, migration, Direction.UP, batch, false);
                notifier.migrationStarted(event);
                apply(migration, event, results);
                results.add(new MigrationResult(name, Outcome.RAN, batch, noQueries()));
                notifier.migrationEnded(event);
            } else {
                results.add(new MigrationResult(name, Outcome.SKIPPED, 0, noQueries()));
                notifier.migrationSkipped(event(name, migration, Direction.UP, 0, false));
            }
            if (step) {
                batch++;
            }
        }
        notifier.migrationsEnded(whole);
        return Collections.unmodifiableList(results);
    }

    /**
     * Rolls back the last batch.
     *
     * @return one result per entry of the batch, newest first; empty when nothing ran
     * @throws MissingMigrationsException if a record to undo has no migration in this migrator,
     *     unless {@link Builder#allowMissingOnRollback()} was set; nothing is undone then
     * @throws IllegalStateException if a connection is inside a transaction
     * @throws MigrationFailedException if a migration fails
     * @throws org.aristonis.diwan.database.QueryException if reading the migrations table fails
     */
    public List<MigrationResult> rollback() {
        ensureNoOpenTransaction();
        return locked(() -> {
            requireCurrentTable();
            return rollBack(recorded(repository::getLast));
        });
    }

    /**
     * Rolls back the most recent migrations, across batches.
     *
     * @param steps how many migrations to roll back, at least 1
     * @return one result per entry, newest first; empty when nothing ran
     * @throws IllegalArgumentException if {@code steps} is below 1
     * @throws MissingMigrationsException if a record to undo has no migration in this migrator,
     *     unless {@link Builder#allowMissingOnRollback()} was set; nothing is undone then
     * @throws IllegalStateException if a connection is inside a transaction
     * @throws MigrationFailedException if a migration fails
     * @throws org.aristonis.diwan.database.QueryException if reading the migrations table fails
     */
    public List<MigrationResult> rollbackSteps(int steps) {
        if (steps < 1) {
            throw new IllegalArgumentException("Steps must be at least 1");
        }
        ensureNoOpenTransaction();
        return locked(() -> {
            requireCurrentTable();
            return rollBack(recorded(() -> repository.getMigrations(steps)));
        });
    }

    /**
     * Rolls back one batch.
     *
     * @param batch the batch number
     * @return one result per entry of the batch, newest first; empty when there is no such batch
     * @throws MissingMigrationsException if a record to undo has no migration in this migrator,
     *     unless {@link Builder#allowMissingOnRollback()} was set; nothing is undone then
     * @throws IllegalStateException if a connection is inside a transaction
     * @throws MigrationFailedException if a migration fails
     * @throws org.aristonis.diwan.database.QueryException if reading the migrations table fails
     */
    public List<MigrationResult> rollbackBatch(int batch) {
        ensureNoOpenTransaction();
        return locked(() -> {
            requireCurrentTable();
            return rollBack(recorded(() -> repository.getMigrationsByBatch(batch)));
        });
    }

    /**
     * Rolls back every migration that ran, newest first.
     *
     * @return one result per entry; empty when nothing ran
     * @throws MissingMigrationsException if a record to undo has no migration in this migrator,
     *     unless {@link Builder#allowMissingOnRollback()} was set; nothing is undone then
     * @throws IllegalStateException if a connection is inside a transaction
     * @throws MigrationFailedException if a migration fails
     * @throws org.aristonis.diwan.database.QueryException if reading the migrations table fails
     */
    public List<MigrationResult> reset() {
        ensureNoOpenTransaction();
        return locked(() -> {
            requireCurrentTable();
            return rollBack(recorded(this::allNewestFirst));
        });
    }

    /**
     * Reports which migrations ran and which are pending.
     *
     * @return the given migrations in name order, then entries of the migrations table that the
     *     migrator was not given; unmodifiable, never {@code null}
     * @throws IllegalStateException if the migrations table exists without a {@code checksum} column
     * @throws org.aristonis.diwan.database.QueryException if reading the migrations table fails
     */
    public List<MigrationStatus> status() {
        requireCurrentTable();
        Map<String, Integer> batches = repository.repositoryExists()
                ? repository.getMigrationBatches() : Collections.<String, Integer>emptyMap();
        List<MigrationStatus> statuses = new ArrayList<>();
        for (String name : byName.keySet()) {
            Integer batch = batches.get(name);
            statuses.add(batch == null
                    ? new MigrationStatus(name, MigrationStatus.State.PENDING, 0)
                    : new MigrationStatus(name, MigrationStatus.State.RAN, batch));
        }
        for (Map.Entry<String, Integer> entry : batches.entrySet()) {
            if (!byName.containsKey(entry.getKey())) {
                statuses.add(new MigrationStatus(entry.getKey(), MigrationStatus.State.MISSING, entry.getValue()));
            }
        }
        return Collections.unmodifiableList(statuses);
    }

    /**
     * Collects the SQL that {@link #run()} would execute, without changing anything.
     *
     * <p>The migrations table is read if it exists and never created. Inside pretend mode a
     * migration that inspects the schema, for example with {@code hasTable}, sees nothing, so its
     * SQL can differ from a real run.
     *
     * @return one result per pending migration, in order: {@link Outcome#PRETENDED} with its SQL,
     *     or {@link Outcome#SKIPPED}
     * @throws IllegalStateException if a connection is inside a transaction
     * @throws MigrationChecksumException if a migration that ran declares a different checksum than
     *     the one recorded
     * @throws MigrationFailedException if a migration throws while its SQL is collected
     * @throws org.aristonis.diwan.database.QueryException if reading the migrations table fails
     */
    public List<MigrationResult> pretendRun() {
        ensureNoOpenTransaction();
        Set<String> ran = Collections.<String>emptySet();
        if (repository.repositoryExists()) {
            repository.requireChecksumColumn();
            MigrationChecksums.validate(repository.checksums(), migrations);
            ran = new HashSet<>(repository.getRan());
        }
        List<MigrationResult> results = new ArrayList<>();
        List<String> pending = pending(ran);
        MigrationsEvent whole = notifier.whole(Direction.UP, true);
        if (pending.isEmpty()) {
            return nothingPending(whole);
        }
        notifier.migrationsStarted(whole);
        for (String name : pending) {
            pretendOne(name, byName.get(name), Direction.UP, 0, results);
        }
        notifier.migrationsEnded(whole);
        return Collections.unmodifiableList(results);
    }

    /**
     * Collects the SQL that {@link #rollback()} would execute, without changing anything.
     *
     * @return one result per entry of the last batch, newest first
     * @throws IllegalStateException if a connection is inside a transaction
     * @throws MigrationFailedException if a migration throws while its SQL is collected
     * @throws org.aristonis.diwan.database.QueryException if reading the migrations table fails
     */
    public List<MigrationResult> pretendRollback() {
        ensureNoOpenTransaction();
        return pretendBack(recorded(repository::getLast));
    }

    /**
     * Collects the SQL that {@link #reset()} would execute, without changing anything.
     *
     * @return one result per entry, newest first
     * @throws IllegalStateException if a connection is inside a transaction
     * @throws MigrationFailedException if a migration throws while its SQL is collected
     * @throws org.aristonis.diwan.database.QueryException if reading the migrations table fails
     */
    public List<MigrationResult> pretendReset() {
        ensureNoOpenTransaction();
        return pretendBack(recorded(this::allNewestFirst));
    }

    // Fails with the upgrade guidance before a read can fail inside a PostgreSQL transaction that the
    // error would leave aborted. A missing table is fine: nothing ran yet.
    private void requireCurrentTable() {
        if (repository.repositoryExists()) {
            repository.requireChecksumColumn();
        }
    }

    private List<String> pending(Set<String> ran) {
        List<String> pending = new ArrayList<>();
        for (String name : byName.keySet()) {
            if (!ran.contains(name)) {
                pending.add(name);
            }
        }
        return pending;
    }

    // Sorted here rather than by reversing a query, so reset's order does not depend on how that
    // query happens to sort.
    private List<MigrationEntry> allNewestFirst() {
        List<MigrationEntry> entries = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : repository.getMigrationBatches().entrySet()) {
            entries.add(new MigrationEntry(entry.getKey(), entry.getValue()));
        }
        entries.sort(Comparator.comparingInt(MigrationEntry::batch).thenComparing(MigrationEntry::name).reversed());
        return entries;
    }

    /**
     * Rewrites the recorded checksum of every migration that ran to the one it declares now.
     *
     * <p>Use it after an intended edit of a migration that already ran. Only migrations in the given
     * list that ran and declare a checksum are touched; a migration that declares none keeps its
     * recorded value. No migration event fires, and the call runs under the migration lock.
     *
     * @param migrations the migrations to repair, never {@code null}; usually {@link #migrations()}
     * @return how many records were rewritten, 0 when the migrations table does not exist
     * @throws NullPointerException if the list or an element is {@code null}
     * @throws IllegalStateException if a connection is inside a transaction, or the migrations
     *     table has no {@code checksum} column
     * @throws org.aristonis.diwan.database.QueryException if reading or writing the migrations table
     *     fails
     */
    public int repairChecksums(List<Migration> migrations) {
        List<Migration> given = new ArrayList<>(Objects.requireNonNull(migrations, "migrations"));
        for (Migration migration : given) {
            MigrationNames.checkName(Objects.requireNonNull(migration, "migration").name());
        }
        ensureNoOpenTransaction();
        return locked(() -> {
            if (!repository.repositoryExists()) {
                return 0;
            }
            repository.requireChecksumColumn();
            Map<String, Optional<String>> stored = repository.checksums();
            int changed = 0;
            for (Migration migration : given) {
                Optional<String> recorded = stored.get(migration.name());
                Optional<String> current = MigrationChecksums.declared(migration);
                if (recorded != null && current.isPresent() && !current.equals(recorded)) {
                    repository.setChecksum(migration.name(), current);
                    changed++;
                }
            }
            return changed;
        });
    }

    /**
     * Removes every recorded checksum, so no migration is checked until the next run records or
     * adopts one again.
     *
     * <p>No migration event fires, and the call runs under the migration lock.
     *
     * @return how many records held a checksum, 0 when the migrations table does not exist
     * @throws IllegalStateException if a connection is inside a transaction, or the migrations
     *     table has no {@code checksum} column
     * @throws org.aristonis.diwan.database.QueryException if reading or writing the migrations table
     *     fails
     */
    public int clearChecksums() {
        ensureNoOpenTransaction();
        return locked(() -> {
            if (!repository.repositoryExists()) {
                return 0;
            }
            repository.requireChecksumColumn();
            int changed = 0;
            for (Map.Entry<String, Optional<String>> entry : repository.checksums().entrySet()) {
                if (entry.getValue().isPresent()) {
                    repository.setChecksum(entry.getKey(), Optional.<String>empty());
                    changed++;
                }
            }
            return changed;
        });
    }

    private <T> T locked(Supplier<T> work) {
        lock.acquire();
        T results;
        try {
            results = work.get();
        } catch (RuntimeException | Error failure) {
            try {
                lock.release();
            } catch (RuntimeException releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
        lock.release();
        return results;
    }

    private List<MigrationResult> rollBack(List<MigrationEntry> entries) {
        if (!isMissingAllowed) {
            List<String> missing = new ArrayList<>();
            for (MigrationEntry entry : entries) {
                if (!byName.containsKey(entry.name())) {
                    missing.add(entry.name());
                }
            }
            if (!missing.isEmpty()) {
                throw new MissingMigrationsException(missing);
            }
        }
        List<MigrationResult> results = new ArrayList<>();
        MigrationsEvent whole = notifier.whole(Direction.DOWN, false);
        if (entries.isEmpty()) {
            return nothingPending(whole);
        }
        notifier.migrationsStarted(whole);
        for (MigrationEntry entry : entries) {
            Migration migration = byName.get(entry.name());
            if (migration == null) {
                results.add(new MigrationResult(entry.name(), Outcome.NOT_FOUND, entry.batch(), noQueries()));
                continue;
            }
            MigrationEvent event = event(entry.name(), migration, Direction.DOWN, entry.batch(), false);
            notifier.migrationStarted(event);
            apply(migration, event, results);
            results.add(new MigrationResult(entry.name(), Outcome.ROLLED_BACK, entry.batch(), noQueries()));
            notifier.migrationEnded(event);
        }
        notifier.migrationsEnded(whole);
        return Collections.unmodifiableList(results);
    }

    private List<MigrationResult> pretendBack(List<MigrationEntry> entries) {
        List<MigrationResult> results = new ArrayList<>();
        MigrationsEvent whole = notifier.whole(Direction.DOWN, true);
        if (entries.isEmpty()) {
            return nothingPending(whole);
        }
        notifier.migrationsStarted(whole);
        for (MigrationEntry entry : entries) {
            Migration migration = byName.get(entry.name());
            if (migration == null) {
                results.add(new MigrationResult(entry.name(), Outcome.NOT_FOUND, entry.batch(), noQueries()));
            } else {
                pretendOne(entry.name(), migration, Direction.DOWN, entry.batch(), results);
            }
        }
        notifier.migrationsEnded(whole);
        return Collections.unmodifiableList(results);
    }

    // Events fire around the pretend scope, never inside it, so a listener's SQL is not collected.
    private void pretendOne(String name, Migration migration, Direction direction, int batch,
            List<MigrationResult> results) {
        if (direction == Direction.UP && !migration.shouldRun()) {
            results.add(new MigrationResult(name, Outcome.SKIPPED, 0, noQueries()));
            notifier.migrationSkipped(event(name, migration, direction, 0, true));
            return;
        }
        MigrationEvent event = event(name, migration, direction, batch, true);
        notifier.migrationStarted(event);
        try {
            List<LoggedQuery> queries = schemaFor(migration).pretend(schema -> invoke(migration, direction, schema));
            results.add(new MigrationResult(name, Outcome.PRETENDED, 0, queries));
        } catch (RuntimeException failure) {
            throw failed(event, Phase.MIGRATION, results, failure);
        }
        notifier.migrationEnded(event);
    }

    /** Runs one migration and records it, undoing both together where the database allows. */
    private void apply(Migration migration, MigrationEvent event, List<MigrationResult> completed) {
        Direction direction = event.direction();
        Schema target = schemaFor(migration);
        boolean isRepositoryConnection = target.connection() == defaultSchema.connection();
        boolean isRecordedInside = false;
        try {
            if (usesTransaction(migration, target)) {
                isRecordedInside = isRepositoryConnection;
                TransactionCallback<Void> work = tx -> {
                    invoke(migration, direction, Schema.of(tx, target.grammar()));
                    if (isRepositoryConnection) {
                        record(new DatabaseMigrationRepository(Schema.of(tx, defaultSchema.grammar()), table),
                                migration, direction, event.batch());
                    }
                    return null;
                };
                try {
                    target.connection().transaction(work);
                } catch (ForeignKeysOffRequiredException needsSwitch) {
                    // The attempt was rolled back and is not a failure, so no failed event fires. Run it again
                    // with enforcement off, so a table rebuild cannot fire other tables' delete actions;
                    // every table is checked before commit.
                    target.grammar().withoutForeignKeyConstraints(target.connection(), tx -> {
                        work.run(tx);
                        target.grammar().checkForeignKeys(tx);
                        return null;
                    });
                }
            } else {
                invoke(migration, direction, target);
            }
        } catch (RuntimeException failure) {
            throw failed(event, Phase.MIGRATION, completed, failure);
        }
        if (!isRecordedInside) {
            try {
                record(repository, migration, direction, event.batch());
            } catch (RuntimeException failure) {
                throw failed(event, Phase.RECORD, completed, failure);
            }
        }
    }

    /** Builds the failure, tells the listeners about it, and returns it for the caller to throw. */
    private MigrationFailedException failed(MigrationEvent event, Phase phase, List<MigrationResult> completed,
            RuntimeException cause) {
        MigrationFailedException failure = new MigrationFailedException(event.name(), event.direction(), phase,
                completed, cause);
        notifier.migrationFailed(event, failure);
        return failure;
    }

    private List<MigrationEntry> recorded(Supplier<List<MigrationEntry>> entries) {
        return repository.repositoryExists() ? entries.get() : Collections.<MigrationEntry>emptyList();
    }

    private MigrationEvent event(String name, Migration migration, Direction direction, int batch, boolean pretend) {
        return new MigrationEvent(direction, name, batch, pretend, schemaFor(migration).connection().getName());
    }

    private static boolean usesTransaction(Migration migration, Schema target) {
        return migration.withinTransaction()
                && target.connection().getDriverInfo().supports(DriverCapability.TRANSACTIONAL_DDL);
    }

    private static void invoke(Migration migration, Direction direction, Schema schema) {
        if (direction == Direction.UP) {
            migration.up(schema);
        } else {
            migration.down(schema);
        }
    }

    private static void record(MigrationRepository target, Migration migration, Direction direction, int batch) {
        if (direction == Direction.UP) {
            target.log(migration.name(), batch, MigrationChecksums.declared(migration).orElse(null));
        } else {
            target.delete(migration.name());
        }
    }

    private Schema schemaFor(Migration migration) {
        String name = migration.connection().orElse(null);
        return name == null ? defaultSchema : connections.get(name);
    }

    private void ensureNoOpenTransaction() {
        boolean isOpen = defaultSchema.connection().transactionLevel() > 0;
        for (Schema schema : connections.values()) {
            isOpen = isOpen || schema.connection().transactionLevel() > 0;
        }
        if (isOpen) {
            throw new IllegalStateException("Migrations cannot run inside an open transaction");
        }
    }

    private static List<LoggedQuery> noQueries() {
        return Collections.emptyList();
    }

    private List<MigrationResult> nothingPending(MigrationsEvent whole) {
        notifier.noPendingMigrations(whole);
        return Collections.emptyList();
    }

    /**
     * Collects what a {@link Migrator} needs and checks it before anything runs.
     *
     * <p>A builder is not thread-safe.
     */
    public static final class Builder {

        private final Schema defaultSchema;
        private final Map<String, Schema> connections = new LinkedHashMap<>();
        private final List<Migration> migrations = new ArrayList<>();
        private String table = DatabaseMigrationRepository.DEFAULT_TABLE;
        private MigrationLock lock = MigrationLock.NONE;
        private boolean isMissingAllowed;
        private final List<MigrationListener> listeners = new ArrayList<>();

        private Builder(Schema defaultSchema) {
            this.defaultSchema = defaultSchema;
        }

        /**
         * Adds a connection that migrations can name through {@link Migration#connection()}.
         *
         * @param name the name migrations use, never {@code null} or blank
         * @param schema the schema builder of that connection, never {@code null}
         * @return this builder
         * @throws NullPointerException if an argument is {@code null}
         * @throws IllegalArgumentException if the name is blank or already added
         */
        public Builder connection(String name, Schema schema) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(schema, "schema");
            if (name.trim().isEmpty()) {
                throw new IllegalArgumentException("A connection name is blank");
            }
            if (connections.containsKey(name)) {
                throw new IllegalArgumentException("The connection " + name + " is added twice");
            }
            connections.put(name, schema);
            return this;
        }

        /**
         * Adds one migration.
         *
         * @param migration the migration, never {@code null}
         * @return this builder
         * @throws NullPointerException if {@code migration} is {@code null}
         */
        public Builder add(Migration migration) {
            migrations.add(Objects.requireNonNull(migration, "migration"));
            return this;
        }

        /**
         * Adds migrations, in any order; they run sorted by name.
         *
         * @param list the migrations, never {@code null}, without {@code null} elements
         * @return this builder
         * @throws NullPointerException if the list or an element is {@code null}
         */
        public Builder migrations(List<? extends Migration> list) {
            for (Migration migration : Objects.requireNonNull(list, "list")) {
                add(migration);
            }
            return this;
        }

        /**
         * Sets the migrations table name, without the connection's prefix.
         *
         * @param name the table name, never {@code null}; default {@code migrations}
         * @return this builder
         * @throws NullPointerException if {@code name} is {@code null}
         */
        public Builder table(String name) {
            this.table = Objects.requireNonNull(name, "name");
            return this;
        }

        /**
         * Sets the lock taken around every run, rollback and reset.
         *
         * @param lock the lock, never {@code null}; default {@link MigrationLock#NONE}
         * @return this builder
         * @throws NullPointerException if {@code lock} is {@code null}
         */
        public Builder lock(MigrationLock lock) {
            this.lock = Objects.requireNonNull(lock, "lock");
            return this;
        }

        /**
         * Adds a listener for migration events; call it again to add more.
         *
         * <p>Listeners are called in the order they were added. See {@link MigrationListener} for
         * what a listener that throws does.
         *
         * @param listener the listener, never {@code null}
         * @return this builder
         * @throws NullPointerException if {@code listener} is {@code null}
         */
        public Builder listener(MigrationListener listener) {
            listeners.add(Objects.requireNonNull(listener, "listener"));
            return this;
        }

        /**
         * Lets rollback and reset go ahead when some records have no migration in this migrator,
         * as Laravel does: the known migrations are undone and the unknown records are reported as
         * {@link MigrationResult.Outcome#NOT_FOUND} and kept. By default such a call is refused.
         *
         * @return this builder
         */
        public Builder allowMissingOnRollback() {
            this.isMissingAllowed = true;
            return this;
        }

        /**
         * Checks everything and builds the migrator.
         *
         * <p>Each migration's name is read once, here; {@link Migration#name()} must not change.
         *
         * @return the migrator, never {@code null}
         * @throws IllegalArgumentException if a migration name is invalid or used twice, a migration
         *     names a connection that was not added, or the table name is invalid
         */
        public Migrator build() {
            Map<String, Migration> named = new LinkedHashMap<>();
            for (Migration migration : migrations) {
                String name = MigrationNames.checkName(migration.name());
                if (named.put(name, migration) != null) {
                    throw new IllegalArgumentException("Two migrations are named " + name);
                }
                String connection = migration.connection().orElse(null);
                if (connection != null && !connections.containsKey(connection)) {
                    throw new IllegalArgumentException(
                            "Migration " + name + " names a connection the migrator was not given");
                }
            }
            return new Migrator(this, named);
        }
    }
}
