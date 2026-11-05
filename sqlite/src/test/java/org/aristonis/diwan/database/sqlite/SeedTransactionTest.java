package org.aristonis.diwan.database.sqlite;

import static org.aristonis.diwan.database.sqlite.ManagerFixtures.createUsers;
import static org.aristonis.diwan.database.sqlite.ManagerFixtures.manager;
import static org.aristonis.diwan.database.sqlite.ManagerFixtures.sqlite;
import static org.aristonis.diwan.database.sqlite.ManagerFixtures.users;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Map;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.aristonis.diwan.database.seeding.SeedContext;
import org.aristonis.diwan.database.seeding.SeedOptions;
import org.aristonis.diwan.database.seeding.Seeder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A seeder run in a transaction leaves nothing behind when it fails, nested seeders included. */
class SeedTransactionTest {

    @TempDir
    Path folder;

    private DatabaseManager manager;

    @BeforeEach
    void open() {
        manager = manager(sqlite("main", folder.resolve("seed.db").toString()));
        manager.migrator("main").add(createUsers()).build().run();
    }

    @AfterEach
    void close() {
        manager.close();
    }

    private static Seeder failingAfterInsert() {
        return new Seeder() {
            @Override
            protected void run(SeedContext context) {
                context.query().table("users").insert(Map.of("name", "before"));
                context.call(() -> users("nested"));
                throw new IllegalStateException("boom");
            }
        };
    }

    private long count() {
        return manager.query("main").table("users").count();
    }

    @Test
    void aFailureInATransactionRollsBackEveryRow() {
        SeedOptions options = SeedOptions.defaults().inTransaction(true);

        assertThrows(IllegalStateException.class, () -> manager.seed(SeedTransactionTest::failingAfterInsert, options));

        assertEquals(0, count());
    }

    @Test
    void aFailureWithoutATransactionKeepsTheEarlierRows() {
        assertThrows(IllegalStateException.class, () -> manager.seed(SeedTransactionTest::failingAfterInsert));

        assertEquals(2, count());
    }

    @Test
    void aSuccessInATransactionCommits() {
        manager.seed(() -> users("Ann", "Bob"), SeedOptions.defaults().inTransaction(true));

        assertEquals(2, count());
    }
}
