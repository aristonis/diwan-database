package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.migrations.MigrationResult;
import org.aristonis.diwan.database.migrations.Migrator;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** Blog-style migrations, as the SQLite sandbox runs them, on PostgreSQL. */
class PostgresMigratorTest {

    static final class Step extends Migration {
        private final String name;
        private final Consumer<Schema> up;
        private final Consumer<Schema> down;

        Step(String name, Consumer<Schema> up, Consumer<Schema> down) {
            this.name = name;
            this.up = up;
            this.down = down;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void up(Schema schema) {
            up.accept(schema);
        }

        @Override
        public void down(Schema schema) {
            down.accept(schema);
        }
    }

    private static List<Migration> blog() {
        return Arrays.asList(
                new Step("2026_01_users", s -> s.create("users", t -> {
                    t.id();
                    t.string("name");
                    t.string("email").unique();
                    t.booleanColumn("active").defaultValue(true);
                    t.timestamps();
                }), s -> s.drop("users")),
                new Step("2026_02_posts", s -> s.create("posts", t -> {
                    t.id();
                    t.foreignId("user_id").constrained().cascadeOnDelete();
                    t.string("title");
                    t.text("body").nullable();
                    t.softDeletes();
                }), s -> s.drop("posts")),
                new Step("2026_03_bio", s -> s.table("users", t -> t.text("bio").nullable()),
                        s -> s.table("users", t -> t.dropColumn("bio"))),
                new Step("2026_04_widen", s -> s.table("posts", t -> t.string("title", 500).change()),
                        s -> s.table("posts", t -> t.string("title").change())));
    }

    @Test
    void blogMigrationsRunRollBackAndRunAgain() {
        Connection db = PostgresServer.newDatabase();
        Schema schema = PostgresSchema.of(db);
        Migrator migrator = Migrator.builder(schema).migrations(blog()).build();

        assertEquals(4, migrator.run().size());
        db.insert("insert into users (name, email) values ('ann', 'a@x')");
        assertEquals(4, migrator.rollback().size());
        assertFalse(schema.hasTable("users"));
        assertEquals(4, migrator.run().size());
        assertTrue(schema.hasColumn("users", "bio"));
    }

    @Test
    void aSecondMigratorWaitsForTheLockThenFindsNothingToRun() throws Exception {
        Connection db = PostgresServer.newDatabase();
        String database = String.valueOf(db.scalar("select current_database()").get());
        PGSimpleDataSource source = PostgresServer.dataSource(database);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        Migration slow = new Step("2026_01_slow", s -> {
            s.create("slow", t -> t.id());
            inside.countDown();
            try {
                finish.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, s -> s.drop("slow"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<MigrationResult>> first = pool.submit(() -> Migrator.builder(PostgresSchema.of(db))
                    .migrations(Arrays.asList(slow)).lock(new PostgresMigrationLock(source)).build().run());
            assertTrue(inside.await(30, TimeUnit.SECONDS));
            Future<List<MigrationResult>> second = pool.submit(() -> Migrator.builder(PostgresSchema.of(db))
                    .migrations(Arrays.asList(slow)).lock(new PostgresMigrationLock(source)).build().run());
            Thread.sleep(300);
            assertFalse(second.isDone());
            finish.countDown();

            assertEquals(1, first.get(30, TimeUnit.SECONDS).size());
            assertTrue(second.get(30, TimeUnit.SECONDS).isEmpty());
        } finally {
            finish.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void aLockStillHeldElsewhereFailsAfterTheWaitNamingTheHolder() {
        Connection db = PostgresServer.newDatabase();
        String database = String.valueOf(db.scalar("select current_database()").get());
        PostgresMigrationLock first = new PostgresMigrationLock(PostgresServer.dataSource(database), "busy");
        PostgresMigrationLock second = new PostgresMigrationLock(PostgresServer.dataSource(database), "busy", 300,
                TimeUnit.MILLISECONDS);
        first.acquire();
        try {
            IllegalStateException error = assertThrows(IllegalStateException.class, second::acquire);
            assertTrue(error.getMessage().contains("pid"), error.getMessage());
        } finally {
            first.release();
        }
        second.acquire();
        second.release();
    }

    @Test
    void aNegativeWaitIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new PostgresMigrationLock(
                PostgresServer.dataSource("postgres"), "x", -1, TimeUnit.SECONDS));
    }

    @Test
    void lockKeysAreStableAndDiffer() {
        assertEquals(PostgresMigrationLock.keyOf("a"), PostgresMigrationLock.keyOf("a"));
        assertNotEquals(PostgresMigrationLock.keyOf("a"), PostgresMigrationLock.keyOf("b"));
    }

    @Test
    void aLockCannotBeTakenTwiceAndReleaseIsSafeWhenNotHeld() {
        Connection db = PostgresServer.newDatabase();
        String database = String.valueOf(db.scalar("select current_database()").get());
        PostgresMigrationLock lock = new PostgresMigrationLock(PostgresServer.dataSource(database), "x");

        lock.release();
        lock.acquire();
        assertThrows(IllegalStateException.class, lock::acquire);
        lock.release();
    }
}
