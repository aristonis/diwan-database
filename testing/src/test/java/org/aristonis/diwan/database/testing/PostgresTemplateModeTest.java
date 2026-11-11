package org.aristonis.diwan.database.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.pgsql.PostgresSchema;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.schema.CopyStrategy;
import org.aristonis.diwan.database.schema.DatabaseVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Template mode against a real PostgreSQL: one template per migration list, one copy per test,
 * sweeping of dead runs, and the refusals. Skipped when the server binaries are missing.
 *
 * <p>Each test uses a migration list no other test used, so its template does not exist yet; the
 * fixtures read the current plan from static fields, as the other kit tests do.
 */
class PostgresTemplateModeTest {

    private static final String ONLY_IN_KIT = "org.aristonis.diwan.database.testing.KitRun#isActive";
    private static final String DEAD_RUN = "dead00";
    private static final String LIVE_RUN = "live00";
    private static final long PARALLEL_HOLD_MILLIS = 200;

    private static volatile List<Migration> migrations;
    private static volatile PGSimpleDataSource server;
    private static volatile DataSource rootSource;
    private static volatile String user;
    private static volatile CopyStrategy strategy;
    private static volatile boolean isDroppingStale;
    private static volatile Consumer<String> onBuilding;

    private static final List<String> SEEN_DATABASES = Collections.synchronizedList(new ArrayList<String>());
    private static final List<Long> SEEN_COUNTS = Collections.synchronizedList(new ArrayList<Long>());
    private static final AtomicBoolean BODY_RAN = new AtomicBoolean();

    /** One root for every fixture run: the fixtures build their extension per test, from the plan. */
    private static final PGSimpleDataSource ROOT = new PGSimpleDataSource() {
        private static final long serialVersionUID = 1L;

        @Override
        public java.sql.Connection getConnection() throws SQLException {
            return rootSource.getConnection();
        }
    };

    private String hash8;

    @BeforeEach
    void plan() {
        server = PostgresServer.dataSource("postgres");
        rootSource = server;
        user = "postgres";
        strategy = null;
        isDroppingStale = false;
        onBuilding = name -> { };
        migrations = PostgresTemplateFixtures.uniqueMigrations();
        hash8 = TemplateName.of(migrations).hash8();
        SEEN_DATABASES.clear();
        SEEN_COUNTS.clear();
        BODY_RAN.set(false);
    }

    @AfterEach
    void removeWhatTheTestMade() {
        PostgresTemplateFixtures.dropAll("diwan\\_t\\_" + hash8 + "\\_%");
        PostgresTemplateFixtures.dropAll("diwan\\_tpl\\_" + hash8 + "%");
    }

    @Test
    void theTemplateIsBuiltOnceAndReusedByTheNextRun() {
        String probe = migrations.get(0).name();

        KitRun.run(Sequential.class).testEvents().assertStatistics(stats -> stats.succeeded(2));
        KitRun.run(Sequential.class).testEvents().assertStatistics(stats -> stats.succeeded(2));

        assertEquals(1, PostgresTemplateFixtures.BUILDS.get(probe).get());
        assertTrue(PostgresTemplateFixtures.exists("diwan_tpl_" + hash8));
        assertEquals(0, PostgresTemplateFixtures.count("diwan\\_tpl\\_" + hash8 + "\\_b\\_%"));
    }

    @Test
    void aCommittedRowDoesNotReachTheNextTest() {
        EngineExecutionResults results = KitRun.run(Sequential.class);

        results.testEvents().assertStatistics(stats -> stats.succeeded(2));
        assertEquals(List.of(1L, 1L), SEEN_COUNTS);
    }

    @Test
    void parametersPointAtTheTestsOwnCopy() {
        KitRun.run(Sequential.class).testEvents().assertStatistics(stats -> stats.succeeded(2));

        assertEquals(2, new HashSet<>(SEEN_DATABASES).size(), SEEN_DATABASES.toString());
        for (String database : SEEN_DATABASES) {
            assertTrue(database.startsWith("diwan_t_" + hash8 + "_" + TemplateName.runId() + "_"), database);
        }
    }

    @Test
    void copiesAreDroppedAfterEachTest() {
        KitRun.run(Sequential.class).testEvents().assertStatistics(stats -> stats.succeeded(2));

        assertEquals(0, PostgresTemplateFixtures.count("diwan\\_t\\_" + hash8 + "\\_%"));
    }

    @Test
    void parallelTestsGetDifferentCopies() {
        EngineExecutionResults results = KitRun.runParallel(Parallel.class);

        results.testEvents().assertStatistics(stats -> stats.succeeded(4));
        assertEquals(4, new HashSet<>(SEEN_DATABASES).size(), SEEN_DATABASES.toString());
    }

    @Test
    void changedMigrationsBuildAnotherTemplateAndKeepTheOld() {
        List<Migration> first = migrations;
        KitRun.run(Sequential.class).testEvents().assertStatistics(stats -> stats.succeeded(2));
        migrations = PostgresTemplateFixtures.uniqueMigrations();
        String secondHash = TemplateName.of(migrations).hash8();

        try {
            KitRun.run(Sequential.class).testEvents().assertStatistics(stats -> stats.succeeded(2));

            assertEquals(1, PostgresTemplateFixtures.BUILDS.get(first.get(0).name()).get());
            assertEquals(1, PostgresTemplateFixtures.BUILDS.get(migrations.get(0).name()).get());
            assertTrue(PostgresTemplateFixtures.exists("diwan_tpl_" + hash8));
            assertTrue(PostgresTemplateFixtures.exists("diwan_tpl_" + secondHash));
        } finally {
            PostgresTemplateFixtures.dropAll("diwan\\_t\\_" + secondHash + "\\_%");
            PostgresTemplateFixtures.dropAll("diwan\\_tpl\\_" + secondHash + "%");
        }
    }

    @Test
    void sweepSkipsTheOwnRun() {
        String own = TemplateName.of(migrations).copy(TemplateName.runId(), 9999);
        PostgresTemplateFixtures.admin().statement("create database \"" + own + "\" template template0");

        KitRun.run(Sequential.class).testEvents().assertStatistics(stats -> stats.succeeded(2));

        assertTrue(PostgresTemplateFixtures.exists(own));
    }

    @Test
    void leftoversOfADeadRunAreSwept() {
        TemplateName name = TemplateName.of(migrations);
        String copy = name.copy(DEAD_RUN, 1);
        String building = name.building(DEAD_RUN);
        Connection admin = PostgresTemplateFixtures.admin();
        admin.statement("create database \"" + copy + "\" template template0");
        admin.statement("create database \"" + building + "\" template template0");

        KitRun.run(Sequential.class).testEvents().assertStatistics(stats -> stats.succeeded(2));

        assertFalse(PostgresTemplateFixtures.exists(copy));
        assertFalse(PostgresTemplateFixtures.exists(building));
    }

    @Test
    void leftoversOfALiveRunAreKept() throws Exception {
        String copy = TemplateName.of(migrations).copy(LIVE_RUN, 1);
        PostgresTemplateFixtures.admin().statement("create database \"" + copy + "\" template template0");
        try (java.sql.Connection holder = server.getConnection()) {
            PostgresTemplateFixtures.execute(holder, "select pg_advisory_lock(?::int, ?::int)",
                    PostgresAdvisoryKeys.RUN_NS, TemplateName.runKey(LIVE_RUN));

            KitRun.run(Sequential.class).testEvents().assertStatistics(stats -> stats.succeeded(2));

            assertTrue(PostgresTemplateFixtures.exists(copy));
        }
    }

    @Test
    void aStaleTemplateIsKeptUnlessAsked() {
        String stale = "diwan_tpl_0badf00d";
        PostgresTemplateFixtures.admin().statement("create database " + stale + " template template0");
        try {
            KitRun.run(Sequential.class).testEvents().assertStatistics(stats -> stats.succeeded(2));

            assertTrue(PostgresTemplateFixtures.exists(stale));
        } finally {
            PostgresTemplateFixtures.dropAll(stale);
        }
    }

    @Test
    void aStaleTemplateIsDroppedWhenAsked() {
        String stale = "diwan_tpl_0badf00d";
        PostgresTemplateFixtures.admin().statement("create database " + stale + " template template0");
        isDroppingStale = true;
        try {
            KitRun.run(Sequential.class).testEvents().assertStatistics(stats -> stats.succeeded(2));

            assertFalse(PostgresTemplateFixtures.exists(stale));
            assertTrue(PostgresTemplateFixtures.exists("diwan_tpl_" + hash8));
        } finally {
            PostgresTemplateFixtures.dropAll(stale);
        }
    }

    @Test
    void aTemplateMadeBetweenTheCheckAndTheCreateIsReused() {
        Connection admin = PostgresTemplateFixtures.admin();
        onBuilding = building -> {
            admin.statement("create database diwan_tpl_" + hash8 + " template template0");
        };

        EngineExecutionResults results = KitRun.run(OnlyCurrentDatabase.class);

        results.testEvents().assertStatistics(stats -> stats.succeeded(1));
        assertEquals(1, SEEN_DATABASES.size());
        assertEquals(0, PostgresTemplateFixtures.count("diwan\\_tpl\\_" + hash8 + "\\_b\\_%"));
    }

    @Test
    void aSessionLeftOnTheTemplateIsReportedNamingIt() throws Exception {
        KitRun.run(OnlyCurrentDatabase.class).testEvents().assertStatistics(stats -> stats.succeeded(1));
        String template = "diwan_tpl_" + hash8;
        PGSimpleDataSource onTemplate = PostgresServer.dataSource(template);
        try (java.sql.Connection held = onTemplate.getConnection()) {
            assertTrue(held.isValid(1));
            EngineExecutionResults results = KitRun.run(OnlyCurrentDatabase.class);

            List<String> messages = failureMessages(results);
            assertTrue(messages.stream().anyMatch(m -> m.contains("session on the template") && m.contains(template)),
                    messages.toString());
        }
    }

    @Test
    void aRoleWithoutCreatedbIsToldWhichPrivilegeToGrant() {
        Connection admin = PostgresTemplateFixtures.admin();
        admin.statement("drop role if exists dwn_nocreate");
        admin.statement("create role dwn_nocreate login nocreatedb");
        try {
            PGSimpleDataSource limited = PostgresServer.dataSource("postgres");
            limited.setUser("dwn_nocreate");
            rootSource = limited;
            user = "dwn_nocreate";

            EngineExecutionResults results = KitRun.run(Marked.class);

            List<String> messages = failureMessages(results);
            assertTrue(messages.stream().anyMatch(m -> m.contains("dwn_nocreate") && m.contains("CREATEDB")),
                    messages.toString());
            assertFalse(BODY_RAN.get());
        } finally {
            admin.statement("drop role if exists dwn_nocreate");
        }
    }

    @Test
    void aFailingDropIsLoggedAndTheTestStillPasses() {
        rootSource = new PostgresTemplateFixtures.FailingStatements(server,
                sql -> sql.toLowerCase().startsWith("drop database") && sql.contains("diwan_t_"));
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger(TestDatabase.class.getName());
        logger.addHandler(handler);
        try {
            KitRun.run(Sequential.class).testEvents().assertStatistics(stats -> stats.succeeded(2));
        } finally {
            logger.removeHandler(handler);
        }

        assertEquals(2, records.stream().filter(r -> r.getLevel() == Level.WARNING).count());
        assertTrue(records.get(0).getMessage().contains("diwan_t_" + hash8), records.get(0).getMessage());
    }

    @Test
    void aPooledRootHoldsOneConnectionAndReturnsItAtTheEnd() {
        PostgresTemplateFixtures.OneSlotPool pool = new PostgresTemplateFixtures.OneSlotPool(server);
        rootSource = pool;

        KitRun.run(Sequential.class).testEvents().assertStatistics(stats -> stats.succeeded(2));

        assertEquals(1, pool.freeSlots());
    }

    @Test
    void anExplicitStrategyIsSentOnServersThatKnowIt() {
        String version = String.valueOf(PostgresTemplateFixtures.admin().scalar("show server_version").orElse("0"));
        assumeTrue(DatabaseVersion.atLeast(version, 15), "STRATEGY needs PostgreSQL 15");
        strategy = CopyStrategy.WAL_LOG;

        KitRun.run(Sequential.class).testEvents().assertStatistics(stats -> stats.succeeded(2));
    }

    private static List<String> failureMessages(EngineExecutionResults results) {
        List<String> messages = new ArrayList<>();
        results.containerEvents().failed().stream().forEach(event -> event.getPayload(TestExecutionResult.class)
                .flatMap(TestExecutionResult::getThrowable)
                .ifPresent(failure -> messages.add(String.valueOf(failure.getMessage()))));
        results.testEvents().failed().stream().forEach(event -> event.getPayload(TestExecutionResult.class)
                .flatMap(TestExecutionResult::getThrowable)
                .ifPresent(failure -> messages.add(String.valueOf(failure.getMessage()))));
        return messages;
    }

    private static DatabaseManager perDatabase(String database) {
        if (database.contains("_b_")) {
            onBuilding.accept(database);
        }
        return PostgresTemplateFixtures.manager(server, database, user);
    }

    private static RefreshDatabase extension() {
        RefreshDatabase db = RefreshDatabase.template(ROOT, PostgresTemplateModeTest::perDatabase,
                PostgresSchema::of, migrations);
        if (strategy != null) {
            db = db.strategy(strategy);
        }
        return isDroppingStale ? db.dropStaleTemplates() : db;
    }

    private static void work(QueryFactory query, Connection connection) {
        SEEN_DATABASES.add(String.valueOf(connection.scalar("select current_database()").orElse("")));
        assertEquals(0, query.table("items").count());
        query.table("items").insert(Map.of("label", "x"));
        SEEN_COUNTS.add(query.table("items").count());
    }

    /** A fixture: two sequential tests, each inserting a committed row. */
    @EnabledIf(ONLY_IN_KIT)
    static class Sequential {
        @RegisterExtension
        final RefreshDatabase db = extension();

        @Test
        void first(QueryFactory query, Connection connection) {
            work(query, connection);
        }

        @Test
        void second(QueryFactory query, Connection connection) {
            work(query, connection);
        }
    }

    /** A fixture: four tests that run at the same time and hold their copy for a moment. */
    @EnabledIf(ONLY_IN_KIT)
    @Execution(ExecutionMode.CONCURRENT)
    static class Parallel {
        @RegisterExtension
        final RefreshDatabase db = extension();

        @Test
        void a(Connection connection) throws InterruptedException {
            hold(connection);
        }

        @Test
        void b(Connection connection) throws InterruptedException {
            hold(connection);
        }

        @Test
        void c(Connection connection) throws InterruptedException {
            hold(connection);
        }

        @Test
        void d(Connection connection) throws InterruptedException {
            hold(connection);
        }

        private static void hold(Connection connection) throws InterruptedException {
            SEEN_DATABASES.add(String.valueOf(connection.scalar("select current_database()").orElse("")));
            Thread.sleep(PARALLEL_HOLD_MILLIS);
        }
    }

    /** A fixture: one test that only reads the name of its database, so any template will do. */
    @EnabledIf(ONLY_IN_KIT)
    static class OnlyCurrentDatabase {
        @RegisterExtension
        final RefreshDatabase db = extension();

        @Test
        void reads(Connection connection) {
            SEEN_DATABASES.add(String.valueOf(connection.scalar("select current_database()").orElse("")));
        }
    }

    /** A fixture: one test that records that its body ran. */
    @EnabledIf(ONLY_IN_KIT)
    static class Marked {
        @RegisterExtension
        final RefreshDatabase db = extension();

        @Test
        void runs(Connection connection) {
            BODY_RAN.set(true);
        }
    }
}
