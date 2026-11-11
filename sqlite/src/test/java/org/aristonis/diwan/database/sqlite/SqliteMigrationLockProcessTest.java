package org.aristonis.diwan.database.sqlite;

import static org.aristonis.diwan.database.sqlite.ManagerFixtures.createUsers;
import static org.aristonis.diwan.database.sqlite.ManagerFixtures.manager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.aristonis.diwan.database.manager.ConnectionConfig;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.aristonis.diwan.database.migrations.FileMigrationLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks the migration lock against a real second process that holds the lock file. */
class SqliteMigrationLockProcessTest {

    private static final long START_TIMEOUT_SECONDS = 60;
    private static final long EXIT_TIMEOUT_SECONDS = 30;
    private static final long SHORT_WAIT_MILLIS = 500;
    private static final long LONG_WAIT_SECONDS = 60;
    private static final long AFTER_KILL_LIMIT_SECONDS = 5;
    private static final long RELEASE_DELAY_MILLIS = 1500;

    @TempDir
    Path folder;

    @Test
    void aSecondMigratorFailsNamingTheLockFileWhileAnotherProcessHoldsIt() throws Exception {
        File db = folder.resolve("held.db").toFile();
        Process child = startHolder(db);
        try {
            awaitHeld(child);

            RuntimeException failure = assertThrows(RuntimeException.class, () -> migrate(db, SHORT_WAIT_MILLIS + "ms"));

            assertTrue(messages(failure).contains("held.db.migrate.lock"), messages(failure));
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void aSecondMigratorWaitsAndSucceedsOnceTheHolderExits() throws Exception {
        File db = folder.resolve("wait.db").toFile();
        Process child = startHolder(db);
        try {
            awaitHeld(child);
            Thread killer = new Thread(() -> {
                try {
                    Thread.sleep(RELEASE_DELAY_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                child.destroyForcibly();
            });
            killer.start();

            migrate(db, LONG_WAIT_SECONDS + "s");

            killer.join(TimeUnit.SECONDS.toMillis(EXIT_TIMEOUT_SECONDS));
            assertTrue(child.waitFor(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void theLockIsFreeWithinSecondsAfterTheHolderIsKilled() throws Exception {
        File db = folder.resolve("killed.db").toFile();
        Process child = startHolder(db);
        try {
            awaitHeld(child);
            child.destroyForcibly();
            assertTrue(child.waitFor(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));

            FileMigrationLock lock = new FileMigrationLock(() -> db, AFTER_KILL_LIMIT_SECONDS, TimeUnit.SECONDS);
            lock.acquire();
            lock.release();
        } finally {
            child.destroyForcibly();
        }
    }

    private static void migrate(File db, String lockWait) {
        ConnectionConfig config = ConnectionConfig.builder("main").driver("sqlite").database(db.toString())
                .option("lock_wait", lockWait).build();
        try (DatabaseManager manager = manager(config)) {
            manager.migrator("main").add(createUsers()).build().run();
            assertEquals(0, manager.query("main").table("users").count());
        }
    }

    private static Process startHolder(File db) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                HolderMain.class.getName(), db.getPath()).redirectErrorStream(true).start();
    }

    // Reads the child's output on a helper thread so a child that never prints cannot block the test forever.
    private static void awaitHeld(Process child) throws Exception {
        String[] line = new String[1];
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                // A JVM may print notices first, such as "Picked up JAVA_TOOL_OPTIONS".
                String next;
                while ((next = in.readLine()) != null && !"held".equals(next)) {
                    continue;
                }
                line[0] = next;
            } catch (IOException e) {
                line[0] = "read failed: " + e.getMessage();
            }
        });
        reader.setDaemon(true);
        reader.start();
        reader.join(TimeUnit.SECONDS.toMillis(START_TIMEOUT_SECONDS));
        assertEquals("held", line[0], "The holder process did not take the lock");
    }

    private static String messages(Throwable e) {
        StringBuilder out = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            out.append(t.getMessage()).append(" | ");
        }
        return out.toString();
    }
}
