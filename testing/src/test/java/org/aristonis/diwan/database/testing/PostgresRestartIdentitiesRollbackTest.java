package org.aristonis.diwan.database.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.pgsql.PostgresSchema;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The {@code restartingIdentities()} option of rollback mode against a throwaway PostgreSQL, run
 * through fixture classes so the extension is exercised as a real test class uses it. Skipped when
 * the server binaries are missing.
 */
class PostgresRestartIdentitiesRollbackTest {

    private static final String ONLY_IN_KIT = "org.aristonis.diwan.database.testing.KitRun#isActive";

    private static volatile Connection connection;
    private static final List<List<Long>> SEEN = Collections.synchronizedList(new ArrayList<>());

    @Test
    void eachTestStartsAtOne() {
        connection = Fixtures.postgres("pgsql-restart-on");
        SEEN.clear();

        KitRun.run(Restarting.class).testEvents().assertStatistics(stats -> stats.succeeded(2).failed(0));

        assertEquals(Arrays.asList(Arrays.asList(1L, 2L, 3L), Arrays.asList(1L, 2L, 3L)), SEEN);
    }

    @Test
    void withoutTheOptionIdsContinue() {
        connection = Fixtures.postgres("pgsql-restart-off");
        SEEN.clear();

        KitRun.run(NotRestarting.class).testEvents().assertStatistics(stats -> stats.succeeded(2).failed(0));

        assertEquals(Arrays.asList(Arrays.asList(1L, 2L, 3L), Arrays.asList(4L, 5L, 6L)), SEEN);
    }

    @Test
    void continuesAfterCommittedRows() {
        connection = Fixtures.postgres("pgsql-restart-seeded");
        SEEN.clear();

        KitRun.run(Seeded.class).testEvents().assertStatistics(stats -> stats.succeeded(2).failed(0));

        assertEquals(Arrays.asList(Arrays.asList(4L, 5L, 6L), Arrays.asList(4L, 5L, 6L)), SEEN);
    }

    /** Inserts three users through the root connection and records their ids. */
    private static void insertThreeUsersAndRecordIds(Connection root) {
        for (String name : Arrays.asList("ann", "bob", "cy")) {
            root.insert("insert into users (name) values (?)", name);
        }
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> row : root.select("select id from users order by id")) {
            ids.add(((Number) row.get("id")).longValue());
        }
        SEEN.add(ids);
    }

    /** Commits three users while the schema is built, so they stay for every test. */
    static final class SeedUsers extends Migration {
        @Override
        public String name() {
            return "2026_01_01_000010_seed_users";
        }

        @Override
        public void up(Schema schema) {
            schema.statement("insert into users (name) values ('seed1'), ('seed2'), ('seed3')");
        }
    }

    /** A fixture: rollback with the option on. */
    @EnabledIf(ONLY_IN_KIT)
    @TestMethodOrder(MethodOrderer.MethodName.class)
    static class Restarting {
        @RegisterExtension
        static final RefreshDatabase DB = RefreshDatabase.rollback(() -> PostgresQuery.of(connection),
                PostgresSchema::of, Fixtures.base()).restartingIdentities();

        @Test
        void t1() {
            insertThreeUsersAndRecordIds(connection);
        }

        @Test
        void t2() {
            insertThreeUsersAndRecordIds(connection);
        }
    }

    /** A fixture: rollback without the option. */
    @EnabledIf(ONLY_IN_KIT)
    @TestMethodOrder(MethodOrderer.MethodName.class)
    static class NotRestarting {
        @RegisterExtension
        static final RefreshDatabase DB = RefreshDatabase.rollback(() -> PostgresQuery.of(connection),
                PostgresSchema::of, Fixtures.base());

        @Test
        void t1() {
            insertThreeUsersAndRecordIds(connection);
        }

        @Test
        void t2() {
            insertThreeUsersAndRecordIds(connection);
        }
    }

    /** A fixture: the option on, and a migration that commits three rows. */
    @EnabledIf(ONLY_IN_KIT)
    @TestMethodOrder(MethodOrderer.MethodName.class)
    static class Seeded {
        @RegisterExtension
        static final RefreshDatabase DB = RefreshDatabase.rollback(() -> PostgresQuery.of(connection),
                PostgresSchema::of, seededMigrations()).restartingIdentities();

        private static List<Migration> seededMigrations() {
            List<Migration> migrations = new ArrayList<>(Fixtures.base());
            migrations.add(new SeedUsers());
            return migrations;
        }

        @Test
        void t1() {
            insertSeededAndRecord();
        }

        @Test
        void t2() {
            insertSeededAndRecord();
        }

        /** Records only the ids this test inserted, not the committed seed rows. */
        private static void insertSeededAndRecord() {
            for (String name : Arrays.asList("ann", "bob", "cy")) {
                connection.insert("insert into users (name) values (?)", name);
            }
            List<Long> ids = new ArrayList<>();
            String sql = "select id from users where name not like 'seed%' order by id";
            for (Map<String, Object> row : connection.select(sql)) {
                ids.add(((Number) row.get("id")).longValue());
            }
            SEEN.add(ids);
        }
    }
}
