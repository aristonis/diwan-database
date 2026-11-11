package org.aristonis.diwan.database.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Arrays;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.aristonis.diwan.database.schema.Schema;

/**
 * Template copies over SQLite files: the template is one file in a directory, and each test gets a
 * copy of that file.
 *
 * <p>The template is built under a building name and moved to its final name only when complete,
 * so a reader never sees a half-built template. Building runs under a file lock on a
 * {@code .build.lock} file, so two JVMs that need the same template build it once. Every database
 * is the file {@code <directory>/<name>.db}; {@code perDatabase} must open exactly that path.
 *
 * <p>A run proves it is alive by holding a lock on {@code diwan_run_<runId>.lock} for its life. The
 * operating system frees the lock when the process dies, which is how {@link #sweep} tells a dead
 * run's leftovers from a live run's files.
 *
 * <p>With {@code isDroppingStale}, {@link #prepare} also deletes the other migration lists'
 * templates in the directory, each under its own build lock so a template another run is building
 * stays.
 *
 * @implNote The template is closed and, when it is in WAL mode, checkpointed before the first copy,
 *     because a copy takes the main file only and would lose rows that still sit in the WAL file.
 */
final class SQLiteFileTemplateCopies implements TemplateCopies {

    private static final Logger LOG = Logger.getLogger(SQLiteFileTemplateCopies.class.getName());

    /** Serialises the builds and sweeps of this JVM; a second lock on a file this JVM holds would throw. */
    private static final Object JVM_LOCK = new Object();
    /** The run lock files this JVM holds, with how many instances share each one. */
    private static final Map<Path, HeldLock> RUN_LOCKS = new HashMap<>();

    private static final String DB_SUFFIX = ".db";
    private static final String MIGRATE_LOCK_SUFFIX = ".migrate.lock";
    private static final List<String> SIDE_SUFFIXES = Collections.unmodifiableList(Arrays.asList("-wal", "-shm", "-journal"));
    private static final Pattern FINAL_TEMPLATE = Pattern.compile("(diwan_tpl_[0-9a-f]{8})\\.db");
    private static final String BUILD_LOCK_SUFFIX = ".build.lock";
    private static final Pattern RUN_LOCK_FILE = Pattern.compile("diwan_run_([0-9a-z]{6})\\.lock");
    private static final int LOCK_ATTEMPTS = 5;
    private static final String OPENED_FILE_SQL = "select file from pragma_database_list where name = 'main'";
    private static final String CHECKPOINT_SQL = "pragma wal_checkpoint(TRUNCATE)";

    private final TemplateName name;
    private final RunConnection run;
    private final Path directory;
    private final Function<Connection, Schema> schema;
    private final boolean isDroppingStale;
    private final String runId = TemplateName.runId();
    private boolean isHoldingRunLock;

    /**
     * Creates the copies of one run over a directory.
     *
     * @param name the template's name, never {@code null}
     * @param run the run's root connection, never {@code null}
     * @param directory the folder that holds the template, the copies and the lock files, never
     *     {@code null}; created at {@link #prepare} when missing
     * @param schema builds the schema that creates and drops database files, never {@code null}
     * @param isDroppingStale whether {@link #prepare} deletes the templates of other migration lists
     * @throws NullPointerException if an argument is {@code null}
     */
    SQLiteFileTemplateCopies(TemplateName name, RunConnection run, Path directory,
            Function<Connection, Schema> schema, boolean isDroppingStale) {
        this.name = Objects.requireNonNull(name, "name");
        this.run = Objects.requireNonNull(run, "run");
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.schema = Objects.requireNonNull(schema, "schema");
        this.isDroppingStale = isDroppingStale;
    }

    @Override
    public void prepare(Function<String, DatabaseManager> perDatabase, Consumer<Connection> migrate) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("Template mode cannot create the directory " + directory, e);
        }
        acquireRunLock(runLockFile());
        isHoldingRunLock = true;
        Path template = file(name.template());
        synchronized (JVM_LOCK) {
            Path buildLock = directory.resolve(name.template() + BUILD_LOCK_SUFFIX);
            try (FileChannel channel = FileChannel.open(buildLock, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE)) {
                FileLock lock = channel.lock();
                try {
                    if (!Files.isRegularFile(template)) {
                        build(template, perDatabase, migrate);
                    }
                    if (isDroppingStale) {
                        dropStaleTemplates();
                    }
                } finally {
                    lock.release();
                }
            } catch (IOException e) {
                throw new IllegalStateException("Template mode cannot build the template file " + template
                        + ": " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void sweep() {
        synchronized (JVM_LOCK) {
            Map<String, Set<Path>> leftovers = findLeftovers();
            for (Map.Entry<String, Set<Path>> entry : leftovers.entrySet()) {
                if (!entry.getKey().equals(runId)) {
                    sweepRun(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    @Override
    public String createCopy(long n) {
        Path copy = file(name.copy(runId, n));
        schema().createDatabase(copy.toString(), file(name.template()).toString());
        return copy.toString();
    }

    @Override
    public void afterOpen(String copy, DatabaseManager manager) {
        Path expected = Paths.get(copy);
        Object opened = manager.connection().scalar(OPENED_FILE_SQL).orElse("");
        if (!isSameFile(expected, String.valueOf(opened))) {
            throw new IllegalStateException("perDatabase must open the path it is given: expected " + expected
                    + ", opened " + (String.valueOf(opened).isEmpty() ? "no file" : opened));
        }
    }

    @Override
    public void dropCopy(String copy) {
        Path path = Paths.get(copy).toAbsolutePath().normalize();
        if (!directory.equals(path.getParent())) {
            throw new IllegalArgumentException("The copy " + copy + " is not in the template directory " + directory);
        }
        dropFiles(path);
    }

    @Override
    public void close() {
        if (isHoldingRunLock) {
            isHoldingRunLock = false;
            releaseRunLock(runLockFile());
        }
    }

    private void build(Path template, Function<String, DatabaseManager> perDatabase, Consumer<Connection> migrate) {
        Path building = file(name.building(runId));
        try {
            TemplateCopies.migrateInto(perDatabase, building.toString(), connection -> {
                migrate.accept(connection);
                connection.unprepared(CHECKPOINT_SQL);
            });
            if (!Files.isRegularFile(building)) {
                throw new IllegalStateException("perDatabase must open the path it is given: expected " + building);
            }
            Files.move(building, template);
        } catch (FileAlreadyExistsException e) {
            // Another run moved its finished template in first; that one is as good as ours.
            LOG.log(Level.FINE, "The template {0} was built by another run; reusing it", template);
        } catch (IOException e) {
            throw new IllegalStateException("Template mode cannot move the finished template to " + template
                    + ": " + e.getMessage(), e);
        } finally {
            dropFiles(building);
        }
    }

    private Map<String, Set<Path>> findLeftovers() {
        Map<String, Set<Path>> byRun = new TreeMap<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String fileName = entry.getFileName().toString();
                Matcher lock = RUN_LOCK_FILE.matcher(fileName);
                if (lock.matches()) {
                    byRun.computeIfAbsent(lock.group(1), id -> new LinkedHashSet<>());
                    continue;
                }
                String stem = stemOf(fileName);
                Optional<String> owner = stem == null ? Optional.empty() : TemplateName.runIdOf(stem);
                owner.ifPresent(id -> byRun.computeIfAbsent(id, key -> new LinkedHashSet<>())
                        .add(directory.resolve(stem + DB_SUFFIX)));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Template mode cannot list the directory " + directory, e);
        }
        return byRun;
    }

    private void sweepRun(String otherRunId, Set<Path> databases) {
        Path lockFile = directory.resolve("diwan_run_" + otherRunId + ".lock");
        FileChannel channel = null;
        FileLock lock = null;
        try {
            if (Files.exists(lockFile)) {
                channel = FileChannel.open(lockFile, StandardOpenOption.WRITE);
                lock = channel.tryLock();
                if (lock == null) {
                    return;
                }
            }
            for (Path database : databases) {
                dropFiles(database);
            }
        } catch (OverlappingFileLockException e) {
            return;
        } catch (IOException | UncheckedIOException e) {
            LOG.log(Level.WARNING, "Template mode cannot sweep the leftovers of run " + otherRunId, e);
            return;
        } finally {
            closeQuietly(lock, channel);
        }
        deleteStaleLock(lockFile);
    }

    /** Releases the lock and closes the channel; the channel is closed even when the release fails. */
    private static void closeQuietly(FileLock lock, FileChannel channel) {
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Template mode cannot release a lock", e);
            }
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Template mode cannot close a lock file", e);
            }
        }
    }

    /**
     * Deletes the template files of other migration lists, except those another run is building.
     * The caller holds the build lock of the current template and the JVM lock.
     */
    private void dropStaleTemplates() {
        List<String> stems = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                Matcher match = FINAL_TEMPLATE.matcher(entry.getFileName().toString());
                if (match.matches() && !match.group(1).equals(name.template())) {
                    stems.add(match.group(1));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Template mode cannot list the directory " + directory, e);
        }
        for (String stem : stems) {
            dropStaleTemplate(stem);
        }
    }

    private void dropStaleTemplate(String stem) {
        Path buildLock = directory.resolve(stem + BUILD_LOCK_SUFFIX);
        FileChannel channel = null;
        FileLock lock = null;
        try {
            channel = FileChannel.open(buildLock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock == null) {
                return;
            }
            dropFiles(file(stem));
            Files.deleteIfExists(buildLock);
        } catch (OverlappingFileLockException e) {
            return;
        } catch (IOException | UncheckedIOException e) {
            LOG.log(Level.WARNING, "Template mode could not drop the stale template " + stem, e);
        } finally {
            closeQuietly(lock, channel);
        }
    }

    private static void deleteStaleLock(Path lockFile) {
        try {
            Files.deleteIfExists(lockFile);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Template mode cannot delete the stale lock file " + lockFile, e);
        }
    }

    private void dropFiles(Path database) {
        schema().dropDatabaseIfExists(database.toString());
        try {
            Files.deleteIfExists(Paths.get(database + MIGRATE_LOCK_SUFFIX));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete the migration lock file of " + database, e);
        }
    }

    private static boolean isSameFile(Path expected, String opened) {
        if (opened.isEmpty()) {
            return false;
        }
        try {
            return Files.isSameFile(expected, Paths.get(opened));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** The name without a side-file suffix and without {@code .db}, or {@code null} for any other file. */
    private static String stemOf(String fileName) {
        String main = fileName;
        for (String suffix : SIDE_SUFFIXES) {
            if (main.endsWith(DB_SUFFIX + suffix)) {
                main = main.substring(0, main.length() - suffix.length());
            }
        }
        if (!main.endsWith(DB_SUFFIX)) {
            return null;
        }
        return main.substring(0, main.length() - DB_SUFFIX.length());
    }

    private Schema schema() {
        return schema.apply(run.connection());
    }

    private Path file(String databaseName) {
        return directory.resolve(databaseName + DB_SUFFIX);
    }

    private Path runLockFile() {
        return directory.resolve("diwan_run_" + runId + ".lock");
    }

    private static void acquireRunLock(Path file) {
        synchronized (RUN_LOCKS) {
            HeldLock held = RUN_LOCKS.get(file);
            if (held != null) {
                held.users++;
                return;
            }
            for (int attempt = 0; attempt < LOCK_ATTEMPTS; attempt++) {
                HeldLock fresh = tryHold(file);
                if (fresh != null) {
                    RUN_LOCKS.put(file, fresh);
                    return;
                }
            }
        }
        throw new IllegalStateException("Template mode cannot hold the run lock file " + file
                + ": another process keeps deleting it");
    }

    /** Locks the file, or returns {@code null} when a sweep deleted it between creating and locking. */
    private static HeldLock tryHold(Path file) {
        FileChannel channel = null;
        try {
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.lock();
            if (Files.exists(file)) {
                return new HeldLock(channel, lock);
            }
            channel.close();
            return null;
        } catch (IOException | RuntimeException e) {
            closeAfterFailure(channel, e);
            throw new IllegalStateException("Template mode cannot hold the run lock file " + file
                    + ": " + e.getMessage(), e);
        }
    }

    private static void closeAfterFailure(FileChannel channel, Exception failure) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException e) {
            failure.addSuppressed(e);
        }
    }

    private static void releaseRunLock(Path file) {
        synchronized (RUN_LOCKS) {
            HeldLock held = RUN_LOCKS.get(file);
            if (held == null || --held.users > 0) {
                return;
            }
            RUN_LOCKS.remove(file);
            IOException failure = null;
            try {
                held.lock.release();
            } catch (IOException e) {
                failure = e;
            } finally {
                failure = closeAndDelete(held.channel, file, failure);
            }
            if (failure != null) {
                throw new IllegalStateException("Template mode cannot release the run lock file " + file, failure);
            }
        }
    }

    /** Closes the channel, which frees the lock too, and deletes the file; returns the first failure, if any. */
    private static IOException closeAndDelete(FileChannel channel, Path file, IOException earlier) {
        IOException first = earlier;
        try {
            channel.close();
        } catch (IOException e) {
            first = firstOf(first, e);
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            first = firstOf(first, e);
        }
        return first;
    }

    private static IOException firstOf(IOException first, IOException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static final class HeldLock {
        private final FileChannel channel;
        private final FileLock lock;
        private int users = 1;

        HeldLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }
    }
}
