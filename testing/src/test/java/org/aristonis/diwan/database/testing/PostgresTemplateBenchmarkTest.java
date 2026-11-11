package org.aristonis.diwan.database.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.pgsql.PostgresSchema;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.schema.CopyStrategy;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.Event;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Times the three ways to give a test a clean PostgreSQL database: truncate, a template copied with
 * {@code FILE_COPY}, and a template copied with {@code WAL_LOG}. Each mode runs the same number of
 * tests over a migration list of about twenty tables, and every test pays for its own pool.
 *
 * <p>It is a measurement, not a check, so it does nothing unless the system property
 * {@code diwan.benchmark} is {@code true}. The result goes to a markdown file named by
 * {@code diwan.benchmark.out}, {@code build/benchmark.md} when unset. It needs the same local
 * PostgreSQL binaries as the other PostgreSQL tests, and a server of version 15 or later for the
 * strategies.
 */
@Tag("benchmark")
class PostgresTemplateBenchmarkTest {

    private static final String ONLY_IN_KIT = "org.aristonis.diwan.database.testing.KitRun#isActive";
    private static final String ENABLED_PROPERTY = "diwan.benchmark";
    private static final String OUT_PROPERTY = "diwan.benchmark.out";
    private static final String DEFAULT_OUT = "build/benchmark.md";
    private static final int TABLES = 20;
    private static final int TESTS = 10;
    private static final double NANOS_PER_MILLI = 1_000_000.0;

    private static volatile Mode mode;
    private static volatile List<Migration> migrations;
    private static volatile QueryFactory truncateQuery;

    /** One root for every run: each test builds its extension, and the run state is keyed by the root. */
    private static final PGSimpleDataSource ROOT = new PGSimpleDataSource() {
        private static final long serialVersionUID = 1L;

        @Override
        public java.sql.Connection getConnection() throws SQLException {
            return PostgresServer.dataSource("postgres").getConnection();
        }
    };

    /** What a mode's run measured, in milliseconds. */
    private static final class Result {
        private final Mode mode;
        private final double setUp;
        private final List<Double> perTest;

        Result(Mode mode, double setUp, List<Double> perTest) {
            this.mode = mode;
            this.setUp = setUp;
            this.perTest = perTest;
        }

        double mean() {
            double sum = 0;
            for (double value : perTest) {
                sum += value;
            }
            return sum / perTest.size();
        }

        double median() {
            List<Double> sorted = new ArrayList<>(perTest);
            Collections.sort(sorted);
            int middle = sorted.size() / 2;
            return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
        }

        double max() {
            return Collections.max(perTest);
        }

        double total() {
            return setUp + mean() * perTest.size();
        }
    }

    private enum Mode {
        TRUNCATE("truncate"), FILE_COPY("template FILE_COPY"), WAL_LOG("template WAL_LOG");

        private final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    @Test
    void comparesTruncateAndTheTwoCopyStrategies() throws IOException {
        assumeTrue(Boolean.getBoolean(ENABLED_PROPERTY), "Set -D" + ENABLED_PROPERTY + "=true to run the benchmark");
        PostgresServer.dataSource("postgres");

        List<Result> results = new ArrayList<>();
        for (Mode each : Mode.values()) {
            results.add(measure(each));
        }

        write(results);
    }

    private static Result measure(Mode each) {
        mode = each;
        migrations = Collections.<Migration>singletonList(new BenchTables(each.name().toLowerCase(Locale.ROOT)
                + "_" + System.nanoTime()));
        truncateQuery = PostgresQuery.of(org.aristonis.diwan.database.Connection.builder().name("bench-truncate")
                .dataSource(PostgresServer.dataSource(PostgresServer.newDatabaseName())).build());
        long started = System.nanoTime();
        EngineExecutionResults run = KitRun.run(Bench.class);
        long wall = System.nanoTime() - started;
        run.testEvents().assertStatistics(stats -> stats.succeeded(TESTS));
        List<Double> perTest = durations(run);
        assertEquals(TESTS, perTest.size());
        double inTests = perTest.stream().mapToDouble(Double::doubleValue).sum();
        return new Result(each, wall / NANOS_PER_MILLI - inTests, perTest);
    }

    /** The time each test took, from its start event to its finish event, set-up callbacks included. */
    private static List<Double> durations(EngineExecutionResults run) {
        Map<String, Event> starts = new HashMap<>();
        run.testEvents().started().stream().forEach(event -> starts.put(event.getTestDescriptor().getUniqueId()
                .toString(), event));
        List<Double> out = new ArrayList<>();
        run.testEvents().finished().stream().forEach(event -> {
            Event start = starts.get(event.getTestDescriptor().getUniqueId().toString());
            out.add(Duration.between(start.getTimestamp(), event.getTimestamp()).toNanos() / NANOS_PER_MILLI);
        });
        return out;
    }

    private static void write(List<Result> results) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("# PostgreSQL test isolation benchmark\n\n");
        out.append(TABLES).append(" tables, ").append(TESTS).append(" tests per mode, one pool per test."
                + " Times in milliseconds.\n\n");
        out.append("| Mode | Set-up once | Mean per test | Median per test | Slowest test | Total |\n");
        out.append("|---|---:|---:|---:|---:|---:|\n");
        for (Result result : results) {
            out.append(String.format(Locale.ROOT, "| %s | %.1f | %.1f | %.1f | %.1f | %.1f |%n", result.mode.label,
                    result.setUp, result.mean(), result.median(), result.max(), result.total()));
        }
        out.append("\nSet-up once is the first connection, the template build or the migrations, and the"
                + " engine's own overhead. For truncate mode the connection is shared by the tests, so"
                + " there is no pool to start.\n");
        Path target = Paths.get(System.getProperty(OUT_PROPERTY, DEFAULT_OUT)).toAbsolutePath();
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(target, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static RefreshDatabase extension() {
        if (mode == Mode.TRUNCATE) {
            return RefreshDatabase.truncate(() -> truncateQuery, PostgresSchema::of, migrations);
        }
        CopyStrategy strategy = mode == Mode.FILE_COPY ? CopyStrategy.FILE_COPY : CopyStrategy.WAL_LOG;
        return RefreshDatabase.template(ROOT, PostgresTemplateBenchmarkTest::perDatabase, PostgresSchema::of,
                migrations).strategy(strategy);
    }

    private static org.aristonis.diwan.database.manager.DatabaseManager perDatabase(String database) {
        return PostgresTemplateFixtures.manager(database);
    }

    /** One work unit of a test: one insert and one read on the first table. */
    private static void work(QueryFactory query) {
        query.table("bench_01").insert(Map.of("label", "x"));
        assertEquals(1, query.table("bench_01").count());
    }

    /** Creates {@link #TABLES} tables, each with a few columns and an index. */
    private static final class BenchTables extends Migration {
        private final String name;

        BenchTables(String name) {
            this.name = "2026_11_11_bench_" + name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void up(Schema schema) {
            for (int i = 1; i <= TABLES; i++) {
                schema.create(String.format(Locale.ROOT, "bench_%02d", i), table -> {
                    table.id();
                    table.string("label");
                    table.integer("amount").nullable();
                    table.timestamps();
                    table.index("label");
                });
            }
        }
    }

    /** A fixture: the tests of one mode; the mode is read when each test's extension is built. */
    @EnabledIf(ONLY_IN_KIT)
    static class Bench {
        @RegisterExtension
        final RefreshDatabase db = extension();

        @Test
        void t01(QueryFactory query) {
            work(query);
        }

        @Test
        void t02(QueryFactory query) {
            work(query);
        }

        @Test
        void t03(QueryFactory query) {
            work(query);
        }

        @Test
        void t04(QueryFactory query) {
            work(query);
        }

        @Test
        void t05(QueryFactory query) {
            work(query);
        }

        @Test
        void t06(QueryFactory query) {
            work(query);
        }

        @Test
        void t07(QueryFactory query) {
            work(query);
        }

        @Test
        void t08(QueryFactory query) {
            work(query);
        }

        @Test
        void t09(QueryFactory query) {
            work(query);
        }

        @Test
        void t10(QueryFactory query) {
            work(query);
        }
    }
}
