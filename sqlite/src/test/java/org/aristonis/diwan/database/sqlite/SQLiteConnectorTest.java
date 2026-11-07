package org.aristonis.diwan.database.sqlite;

import static org.aristonis.diwan.database.sqlite.ManagerFixtures.createUsers;
import static org.aristonis.diwan.database.sqlite.ManagerFixtures.manager;
import static org.aristonis.diwan.database.sqlite.ManagerFixtures.sqlite;
import static org.aristonis.diwan.database.sqlite.ManagerFixtures.step;
import static org.aristonis.diwan.database.sqlite.ManagerFixtures.users;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.aristonis.diwan.database.exception.DatabaseConfigException;
import org.aristonis.diwan.database.exception.ForeignKeyViolationException;
import org.aristonis.diwan.database.manager.ConnectionConfig;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteConnectorTest {

    private static final int WAIT_SECONDS = 10;
    private static final long SETTLE_MILLIS = 200;

    @TempDir
    Path folder;

    @Test
    void aFileDatabaseMigratesThenSeedsThroughTheManager() {
        try (DatabaseManager manager = manager(sqlite("main", folder.resolve("app.db").toString()))) {
            manager.migrator("main").add(createUsers()).build().run();
            manager.seed(() -> users("Ann", "Bob"));

            assertEquals(2, manager.query("main").table("users").count());
        }
    }

    @Test
    void anInMemoryDatabaseSurvivesAcrossSessionsAndMigratesThenSeeds() {
        try (DatabaseManager manager = manager(sqlite("mem", ":memory:"))) {
            manager.migrator("mem").add(createUsers()).build().run();
            manager.seed(() -> users("Ann"));
            manager.seed(() -> users("Bob"));

            assertEquals(2, manager.query().table("users").count());
            assertTrue(manager.schema().hasTable("users"));
        }
    }

    @Test
    void purgeDropsTheInMemoryDatabase() {
        try (DatabaseManager manager = manager(sqlite("mem", ":memory:"))) {
            manager.migrator("mem").add(createUsers()).build().run();
            assertTrue(manager.schema("mem").hasTable("users"));

            manager.purge("mem");

            assertFalse(manager.schema("mem").hasTable("users"));
        }
    }

    @Test
    void twoInMemoryConnectionsAreIndependentDatabases() {
        try (DatabaseManager manager = manager(sqlite("a", ":memory:"), sqlite("b", ":memory:"))) {
            manager.migrator("a").add(createUsers()).build().run();

            assertTrue(manager.schema("a").hasTable("users"));
            assertFalse(manager.schema("b").hasTable("users"));
        }
    }

    @Test
    void foreignKeysAreEnforcedByDefault() {
        try (DatabaseManager manager = manager(sqlite("main", folder.resolve("fk.db").toString()))) {
            manager.migrator("main").add(parentAndChild()).build().run();

            assertThrows(ForeignKeyViolationException.class,
                    () -> manager.query().table("children").insert(java.util.Map.of("parent_id", 99)));
        }
    }

    @Test
    void theForeignKeysOptionTurnsEnforcementOff() {
        ConnectionConfig config = ConnectionConfig.builder("main").driver("sqlite")
                .database(folder.resolve("nofk.db").toString()).option("foreign_keys", "false").build();
        try (DatabaseManager manager = manager(config)) {
            manager.migrator("main").add(parentAndChild()).build().run();

            manager.query().table("children").insert(java.util.Map.of("parent_id", 99));

            assertEquals(1, manager.query().table("children").count());
        }
    }

    @Test
    void theUrlIsBuiltFromThePathOrTheMemoryName() {
        assertEquals("jdbc:sqlite:/tmp/x.db?foreign_keys=true", SQLiteConnector.jdbcUrl(sqlite("main", "/tmp/x.db")));
        assertEquals("jdbc:sqlite::memory:?foreign_keys=true", SQLiteConnector.jdbcUrl(sqlite("my conn", ":memory:")));
    }

    @Test
    void anExplicitUrlIsUsedAsGivenPlusTheForeignKeysOption() {
        ConnectionConfig config = ConnectionConfig.builder("main").driver("sqlite").url("jdbc:sqlite:/tmp/y.db").build();
        ConnectionConfig off = ConnectionConfig.builder("main").driver("sqlite").url("jdbc:sqlite:/tmp/y.db?a=b")
                .option("foreign_keys", "false").build();
        ConnectionConfig own = ConnectionConfig.builder("main").driver("sqlite")
                .url("jdbc:sqlite:/tmp/y.db?foreign_keys=false").build();

        assertEquals("jdbc:sqlite:/tmp/y.db?foreign_keys=true", SQLiteConnector.jdbcUrl(config));
        assertEquals("jdbc:sqlite:/tmp/y.db?a=b&foreign_keys=false", SQLiteConnector.jdbcUrl(off));
        assertEquals("jdbc:sqlite:/tmp/y.db?foreign_keys=false", SQLiteConnector.jdbcUrl(own));
    }

    @Test
    void memoryUrlsAreRecognized() {
        assertTrue(SQLiteConnector.isMemoryUrl("jdbc:sqlite::memory:"));
        assertTrue(SQLiteConnector.isMemoryUrl("jdbc:sqlite::memory:?foreign_keys=true"));
        assertTrue(SQLiteConnector.isMemoryUrl("jdbc:sqlite:file::memory:"));
        assertTrue(SQLiteConnector.isMemoryUrl("jdbc:sqlite:file:x?mode=memory&cache=shared"));
        assertFalse(SQLiteConnector.isMemoryUrl("jdbc:sqlite:/tmp/x.db"));
        assertFalse(SQLiteConnector.isMemoryUrl("jdbc:postgresql://h/db"));
    }

    @Test
    void aUserMemoryUrlGetsOneSharedConnectionAndForeignKeys() {
        ConnectionConfig config = ConnectionConfig.builder("mem").driver("sqlite").url("jdbc:sqlite::memory:").build();
        try (DatabaseManager manager = manager(config)) {
            manager.migrator("mem").add(parentAndChild()).build().run();

            assertTrue(manager.schema("mem").hasTable("children"));
            assertThrows(ForeignKeyViolationException.class,
                    () -> manager.query().table("children").insert(java.util.Map.of("parent_id", 99)));
        }
    }

    @Test
    void sessionsOnDifferentThreadsSeeTheSameInMemoryTables() throws Exception {
        try (DatabaseManager manager = manager(sqlite("mem", ":memory:"))) {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                pool.submit(() -> manager.migrator("mem").add(createUsers()).build().run())
                        .get(WAIT_SECONDS, TimeUnit.SECONDS);
                pool.submit(() -> manager.query("mem").table("users").insert(java.util.Map.of("name", "Ann")))
                        .get(WAIT_SECONDS, TimeUnit.SECONDS);

                long count = pool.submit(() -> manager.query("mem").table("users").count())
                        .get(WAIT_SECONDS, TimeUnit.SECONDS);

                assertEquals(1L, count);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void aSecondSessionWaitsForTheFirstThenProceeds() throws Exception {
        try (DatabaseManager manager = manager(sqlite("mem", ":memory:"))) {
            manager.migrator("mem").add(createUsers()).build().run();
            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = pool.submit(() -> manager.connection("mem").transaction(tx -> {
                    holding.countDown();
                    awaitQuietly(release);
                    return null;
                }));
                assertTrue(holding.await(WAIT_SECONDS, TimeUnit.SECONDS));
                Future<Long> second = pool.submit(() -> manager.query("mem").table("users").count());

                Thread.sleep(SETTLE_MILLIS);
                assertFalse(second.isDone());
                release.countDown();

                first.get(WAIT_SECONDS, TimeUnit.SECONDS);
                assertEquals(0L, second.get(WAIT_SECONDS, TimeUnit.SECONDS));
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }
    }

    @Test
    void aSessionThatWaitsLongerThanBusyTimeoutFailsNamingTheDatabase() throws Exception {
        ConnectionConfig config = ConnectionConfig.builder("mem").driver("sqlite").database(":memory:")
                .option("busy_timeout", "100").build();
        try (DatabaseManager manager = manager(config)) {
            manager.migrator("mem").add(createUsers()).build().run();
            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<?> first = pool.submit(() -> manager.connection("mem").transaction(tx -> {
                    holding.countDown();
                    awaitQuietly(release);
                    return null;
                }));
                assertTrue(holding.await(WAIT_SECONDS, TimeUnit.SECONDS));

                RuntimeException e = assertThrows(RuntimeException.class,
                        () -> manager.query("mem").table("users").count());

                assertTrue(messages(e).contains("In-memory SQLite database [mem] is busy: one session at a time"),
                        messages(e));
                release.countDown();
                first.get(WAIT_SECONDS, TimeUnit.SECONDS);
                assertEquals(0L, manager.query("mem").table("users").count());
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }
    }

    @Test
    void aBadBusyTimeoutIsRefused() {
        ConnectionConfig config = ConnectionConfig.builder("mem").driver("sqlite").database(":memory:")
                .option("busy_timeout", "soon").build();
        try (DatabaseManager manager = manager(config)) {
            RuntimeException e = assertThrows(RuntimeException.class, () -> manager.connection("mem"));

            assertTrue(messages(e).contains("busy_timeout"), messages(e));
        }
    }

    @Test
    void aMissingDatabaseNamesTheConnectionAndKey() {
        ConnectionConfig config = ConnectionConfig.builder("main").driver("sqlite").build();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SQLiteConnector.jdbcUrl(config));

        assertTrue(e.getMessage().contains("main") && e.getMessage().contains("database"), e.getMessage());
    }

    @Test
    void aBadForeignKeysValueIsRefused() {
        ConnectionConfig config = ConnectionConfig.builder("main").driver("sqlite").database("x.db")
                .option("foreign_keys", "maybe").build();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SQLiteConnector.jdbcUrl(config));

        assertTrue(e.getMessage().contains("foreign_keys"), e.getMessage());
    }

    @Test
    void noTransactionModeByDefault() {
        ConnectionConfig config = ConnectionConfig.builder("main").driver("sqlite").database("/tmp/z.db").build();

        assertEquals("jdbc:sqlite:/tmp/z.db?foreign_keys=true", SQLiteConnector.jdbcUrl(config));
    }

    @Test
    void transactionModeIsAppendedInUpperCase() {
        ConnectionConfig file = ConnectionConfig.builder("main").driver("sqlite").database("/tmp/z.db")
                .option("transaction_mode", "immediate").build();
        ConnectionConfig memory = ConnectionConfig.builder("mem").driver("sqlite").database(":memory:")
                .option("transaction_mode", "Exclusive").build();

        assertEquals("jdbc:sqlite:/tmp/z.db?foreign_keys=true&transaction_mode=IMMEDIATE",
                SQLiteConnector.jdbcUrl(file));
        assertEquals("jdbc:sqlite::memory:?foreign_keys=true&transaction_mode=EXCLUSIVE",
                SQLiteConnector.jdbcUrl(memory));
    }

    @Test
    void urlTransactionModeWins() {
        ConnectionConfig config = ConnectionConfig.builder("main").driver("sqlite")
                .url("jdbc:sqlite:/tmp/z.db?TRANSACTION_MODE=deferred").option("transaction_mode", "immediate").build();

        assertEquals("jdbc:sqlite:/tmp/z.db?TRANSACTION_MODE=deferred&foreign_keys=true",
                SQLiteConnector.jdbcUrl(config));
    }

    @Test
    void badTransactionModeIsRefused() {
        ConnectionConfig config = ConnectionConfig.builder("main").driver("sqlite").database("x.db")
                .option("transaction_mode", "eager").build();

        DatabaseConfigException e = assertThrows(DatabaseConfigException.class, () -> SQLiteConnector.jdbcUrl(config));

        assertTrue(e.getMessage().contains("main") && e.getMessage().contains("transaction_mode"), e.getMessage());
    }

    @Test
    void busyTimeoutAppendedWhenSet() {
        ConnectionConfig config = ConnectionConfig.builder("main").driver("sqlite").database("/tmp/z.db")
                .option("busy_timeout", "250").build();
        ConnectionConfig own = ConnectionConfig.builder("main").driver("sqlite")
                .url("jdbc:sqlite:/tmp/z.db?busy_timeout=9").option("busy_timeout", "250").build();

        assertEquals("jdbc:sqlite:/tmp/z.db?foreign_keys=true&busy_timeout=250", SQLiteConnector.jdbcUrl(config));
        assertEquals("jdbc:sqlite:/tmp/z.db?busy_timeout=9&foreign_keys=true", SQLiteConnector.jdbcUrl(own));
    }

    @Test
    void theDriverNameIsSqlite() {
        assertEquals("sqlite", new SQLiteConnector().driver());
    }

    private static String messages(Throwable e) {
        StringBuilder out = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            out.append(t.getMessage()).append(" | ");
        }
        return out.toString();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static org.aristonis.diwan.database.migrations.Migration parentAndChild() {
        return step("2026_01_family", schema -> {
            schema.create("parents", t -> t.id());
            schema.create("children", t -> {
                t.id();
                t.foreignId("parent_id").constrained("parents");
            });
        }, schema -> {
            schema.drop("children");
            schema.drop("parents");
        });
    }
}
