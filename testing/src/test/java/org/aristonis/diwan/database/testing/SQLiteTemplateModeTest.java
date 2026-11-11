package org.aristonis.diwan.database.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** The SQLite file copies: template built once, copies isolated, sweep rules, the opened-path check. */
class SQLiteTemplateModeTest {

    private static final int ROWS = 50;
    private static final int THREADS = 8;
    private static final long WAIT_SECONDS = 60;
    private static final String DEAD_RUN = "zzzzzz";
    private static final String LIVE_RUN = "yyyyyy";
    private static final String STALE_TEMPLATE = "diwan_tpl_0badc0de";

    @TempDir
    Path folder;

    private final TemplateName name = TemplateName.of(Fixtures.base());
    private final List<AutoCloseable> toClose = new ArrayList<>();
    private final AtomicInteger migrations = new AtomicInteger();

    private RunConnection run;

    @BeforeEach
    void openRun() {
        SQLiteDataSource root = new SQLiteDataSource();
        root.setUrl("jdbc:sqlite:" + folder.resolve("root.db"));
        run = RunConnection.open(root);
    }

    @AfterEach
    void closeAll() throws Exception {
        for (AutoCloseable closeable : toClose) {
            closeable.close();
        }
        run.close();
    }

    /** Creates the users table and fills it; used by the fork test's child JVM too. */
    static void migrate(Connection connection, int rows, long delayMillis) {
        sleep(delayMillis);
        connection.unprepared("create table users (id integer primary key, name text)");
        for (int i = 0; i < rows; i++) {
            connection.unprepared("insert into users (name) values ('user" + i + "')");
        }
    }

    static long count(String file) {
        try (DatabaseManager manager = FakeCopies.manager(file)) {
            return ((Number) manager.connection().scalar("select count(*) from users").orElseThrow()).longValue();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }

    private SQLiteFileTemplateCopies copies() {
        SQLiteFileTemplateCopies copies = new SQLiteFileTemplateCopies(name, run, folder, SQLiteSchema::of, false);
        toClose.add(copies);
        return copies;
    }

    private SQLiteFileTemplateCopies prepared(boolean isDroppingStale) {
        SQLiteFileTemplateCopies copies = new SQLiteFileTemplateCopies(name, run, folder, SQLiteSchema::of,
                isDroppingStale);
        toClose.add(copies);
        copies.prepare(FakeCopies::manager, c -> migrate(c, 2, 0));
        return copies;
    }

    private SQLiteFileTemplateCopies prepared() {
        SQLiteFileTemplateCopies copies = copies();
        copies.prepare(FakeCopies::manager, c -> {
            migrations.incrementAndGet();
            migrate(c, 2, 0);
        });
        return copies;
    }

    @Test
    void theTemplateIsBuiltOnceAndReused() {
        prepared();
        prepared();

        assertEquals(1, migrations.get());
        assertTrue(Files.isRegularFile(folder.resolve(name.template() + ".db")));
        assertFalse(Files.exists(folder.resolve(name.building(TemplateName.runId()) + ".db")));
        assertFalse(Files.exists(folder.resolve(name.building(TemplateName.runId()) + ".db.migrate.lock")));
    }

    @Test
    void aMissingDirectoryIsCreated() {
        Path nested = folder.resolve("a").resolve("b");
        SQLiteFileTemplateCopies copies = new SQLiteFileTemplateCopies(name, run, nested, SQLiteSchema::of, false);
        toClose.add(copies);

        copies.prepare(FakeCopies::manager, c -> migrate(c, 1, 0));

        assertTrue(Files.isRegularFile(nested.resolve(name.template() + ".db")));
    }

    @Test
    void aCommittedRowInOneCopyReachesNeitherTheTemplateNorAnotherCopy() {
        SQLiteFileTemplateCopies copies = prepared();
        String first = copies.createCopy(1);
        String second = copies.createCopy(2);
        try (DatabaseManager manager = FakeCopies.manager(first)) {
            manager.connection().unprepared("insert into users (name) values ('extra')");
        }

        assertEquals(3, count(first));
        assertEquals(2, count(second));
        assertEquals(2, count(folder.resolve(name.template() + ".db").toString()));
    }

    @Test
    void copiesMadeInParallelAreDistinctAndComplete() throws Exception {
        SQLiteFileTemplateCopies copies = prepared();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                long n = i;
                futures.add(pool.submit(() -> copies.createCopy(n)));
            }
            Set<String> names = new HashSet<>();
            for (Future<String> future : futures) {
                String copy = future.get(WAIT_SECONDS, TimeUnit.SECONDS);
                names.add(copy);
                assertEquals(2, count(copy));
            }
            assertEquals(THREADS, names.size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aWalTemplateIsCopiedWithAllItsRows() {
        SQLiteFileTemplateCopies copies = copies();
        copies.prepare(FakeCopies::manager, c -> {
            c.unprepared("pragma journal_mode = wal");
            migrate(c, ROWS, 0);
        });

        String copy = copies.createCopy(1);

        assertEquals(ROWS, count(copy));
        assertFalse(Files.exists(Path.of(folder.resolve(name.template() + ".db") + "-wal")));
    }

    @Test
    void aManagerOnAnotherFileIsRefused() {
        SQLiteFileTemplateCopies copies = prepared();
        String copy = copies.createCopy(1);
        String other = folder.resolve("other.db").toString();

        try (DatabaseManager manager = FakeCopies.manager(other)) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> copies.afterOpen(copy, manager));

            assertTrue(error.getMessage().contains("perDatabase must open the path it is given"),
                    error.getMessage());
        }
    }

    @Test
    void aManagerOnTheCopyIsAccepted() {
        SQLiteFileTemplateCopies copies = prepared();
        String copy = copies.createCopy(1);

        try (DatabaseManager manager = FakeCopies.manager(copy)) {
            copies.afterOpen(copy, manager);
        }
    }

    @Test
    void dropCopyRemovesTheFileAndItsSideFilesAndRefusesOtherFolders() throws IOException {
        SQLiteFileTemplateCopies copies = prepared();
        String copy = copies.createCopy(1);
        Files.createFile(Path.of(copy + "-wal"));

        copies.dropCopy(copy);

        assertFalse(Files.exists(Path.of(copy)));
        assertFalse(Files.exists(Path.of(copy + "-wal")));
        String elsewhere = folder.getParent().resolve("x.db").toString();
        assertThrows(IllegalArgumentException.class, () -> copies.dropCopy(elsewhere));
    }

    @Test
    void sweepSkipsTheOwnRun() {
        SQLiteFileTemplateCopies copies = prepared();
        String copy = copies.createCopy(1);

        copies.sweep();

        assertTrue(Files.exists(Path.of(copy)));
        assertTrue(Files.exists(folder.resolve("diwan_run_" + TemplateName.runId() + ".lock")));
    }

    @Test
    void sweepDeletesTheLeftoversAndStaleLockFilesOfDeadRuns() throws IOException {
        SQLiteFileTemplateCopies copies = prepared();
        List<Path> dead = new ArrayList<>();
        dead.add(touch(name.copy(DEAD_RUN, 1) + ".db"));
        dead.add(touch(name.copy(DEAD_RUN, 1) + ".db-wal"));
        dead.add(touch(name.copy(DEAD_RUN, 1) + ".db.migrate.lock"));
        dead.add(touch(name.building(DEAD_RUN) + ".db"));
        dead.add(touch("diwan_run_" + DEAD_RUN + ".lock"));
        dead.add(touch(name.copy("xxxxxx", 3) + ".db"));
        Path template = folder.resolve(name.template() + ".db");

        copies.sweep();

        for (Path path : dead) {
            assertFalse(Files.exists(path), path.toString());
        }
        assertTrue(Files.exists(template));
    }

    @Test
    void sweepKeepsTheCopiesOfARunThatHoldsItsLock() throws IOException {
        SQLiteFileTemplateCopies copies = prepared();
        Path liveCopy = touch(name.copy(LIVE_RUN, 1) + ".db");
        Path liveLock = touch("diwan_run_" + LIVE_RUN + ".lock");

        try (FileChannel channel = FileChannel.open(liveLock, StandardOpenOption.WRITE);
                FileLock lock = channel.lock()) {
            copies.sweep();

            assertTrue(lock.isValid());
            assertTrue(Files.exists(liveCopy));
            assertTrue(Files.exists(liveLock));
        }
    }

    @Test
    void closeReleasesAndDeletesTheRunLock() {
        SQLiteFileTemplateCopies copies = prepared();
        Path lockFile = folder.resolve("diwan_run_" + TemplateName.runId() + ".lock");
        assertTrue(Files.exists(lockFile));

        copies.close();

        assertFalse(Files.exists(lockFile));
        assertNotEquals(DEAD_RUN, TemplateName.runId());
    }

    @Test
    void staleTemplatesAreKeptByDefault() throws IOException {
        Path stale = touch(STALE_TEMPLATE + ".db");

        prepared(false);

        assertTrue(Files.exists(stale));
    }

    @Test
    void staleTemplatesAreDroppedWithTheirSideFilesAndBuildLockButTheCurrentOneStays() throws IOException {
        List<Path> stale = new ArrayList<>();
        stale.add(touch(STALE_TEMPLATE + ".db"));
        stale.add(touch(STALE_TEMPLATE + ".db-wal"));
        stale.add(touch(STALE_TEMPLATE + ".db-shm"));
        stale.add(touch(STALE_TEMPLATE + ".db-journal"));
        stale.add(touch(STALE_TEMPLATE + ".db.migrate.lock"));
        stale.add(touch(STALE_TEMPLATE + ".build.lock"));
        Path unrelated = touch("notes.db");

        prepared(true);

        for (Path path : stale) {
            assertFalse(Files.exists(path), path.toString());
        }
        assertTrue(Files.exists(folder.resolve(name.template() + ".db")));
        assertTrue(Files.exists(unrelated));
    }

    @Test
    void aStaleTemplateAnotherRunIsBuildingIsKept() throws IOException {
        Path stale = touch(STALE_TEMPLATE + ".db");
        Path buildLock = touch(STALE_TEMPLATE + ".build.lock");

        try (FileChannel channel = FileChannel.open(buildLock, StandardOpenOption.WRITE);
                FileLock lock = channel.lock()) {
            prepared(true);

            assertTrue(lock.isValid());
            assertTrue(Files.exists(stale));
        }
    }

    @Test
    void twoTemplatesInOneDirectoryShareTheRunLockUntilTheLastCloses() {
        Path lockFile = folder.resolve("diwan_run_" + TemplateName.runId() + ".lock");
        SQLiteFileTemplateCopies first = prepared();
        SQLiteFileTemplateCopies second = new SQLiteFileTemplateCopies(TemplateName.of(Fixtures.withExtraSchema()),
                run, folder, SQLiteSchema::of, false);
        toClose.add(second);
        second.prepare(FakeCopies::manager, c -> migrate(c, 1, 0));

        first.close();

        assertTrue(Files.exists(lockFile));
        assertThrows(OverlappingFileLockException.class, () -> {
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
                channel.tryLock();
            }
        });

        second.close();

        assertFalse(Files.exists(lockFile));
    }

    private Path touch(String fileName) throws IOException {
        return Files.createFile(folder.resolve(fileName));
    }
}
