package org.aristonis.diwan.database.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.sqlite.SQLiteDataSource;

/**
 * Two migration lists on one root data source share one JDBC session, so their copy creation and
 * removal must not overlap even when their tests run in parallel.
 */
class SharedRootTemplatesTest {

    private static final String ONLY_IN_KIT = "org.aristonis.diwan.database.testing.KitRun#isActive";
    private static final long HOLD_MILLIS = 30;

    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final AtomicInteger MAX_IN_FLIGHT = new AtomicInteger();
    private static volatile Path directory;
    private static volatile SQLiteDataSource current;

    private static final SQLiteDataSource ROOT = new SQLiteDataSource() {
        @Override
        public java.sql.Connection getConnection() throws SQLException {
            return current.getConnection();
        }
    };

    @TempDir
    Path folder;

    @BeforeEach
    void reset() {
        directory = folder;
        IN_FLIGHT.set(0);
        MAX_IN_FLIGHT.set(0);
        current = new SQLiteDataSource();
        current.setUrl("jdbc:sqlite:" + folder.resolve("root.db"));
    }

    @Test
    void twoListsOnOneRootNeverUseItsConnectionAtTheSameTime() {
        EngineExecutionResults results = KitRun.runParallel(FirstList.class, SecondList.class);

        results.testEvents().assertStatistics(stats -> stats.succeeded(8));
        assertEquals(1, MAX_IN_FLIGHT.get());
    }

    private static RefreshDatabase extension(String table) {
        List<Migration> migrations = Collections.<Migration>singletonList(new CreateTable(table));
        return RefreshDatabase.template(ROOT, FakeCopies::manager, SQLiteSchema::of, migrations)
                .withCopies((settings, run) -> new Overlapping(new FakeCopies(directory.resolve(table), false, false),
                        directory.resolve(table)));
    }

    /** Creates one table, so each list has its own template name. */
    private static final class CreateTable extends Migration {
        private final String table;

        CreateTable(String table) {
            this.table = table;
        }

        @Override
        public String name() {
            return "create_" + table;
        }

        @Override
        public void up(Schema schema) {
            schema.create(table, blueprint -> blueprint.id());
        }
    }

    /** Counts how many calls are inside the engine at once, across both lists. */
    private static final class Overlapping implements TemplateCopies {
        private final FakeCopies inner;

        Overlapping(FakeCopies inner, Path own) {
            this.inner = inner;
            own.toFile().mkdirs();
        }

        @Override
        public void prepare(Function<String, DatabaseManager> perDatabase, Consumer<Connection> migrate) {
            inner.prepare(perDatabase, migrate);
        }

        @Override
        public void sweep() {
            inner.sweep();
        }

        @Override
        public String createCopy(long n) {
            return counted(() -> inner.createCopy(n));
        }

        @Override
        public void dropCopy(String copy) {
            counted(() -> {
                inner.dropCopy(copy);
                return null;
            });
        }

        @Override
        public void close() {
            inner.close();
        }

        private static String counted(java.util.function.Supplier<String> work) {
            int now = IN_FLIGHT.incrementAndGet();
            MAX_IN_FLIGHT.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(HOLD_MILLIS);
                return work.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted", e);
            } finally {
                IN_FLIGHT.decrementAndGet();
            }
        }
    }

    /** A fixture: four parallel tests on the first migration list. */
    @EnabledIf(ONLY_IN_KIT)
    @Execution(ExecutionMode.CONCURRENT)
    static class FirstList {
        @RegisterExtension
        static final RefreshDatabase DB = extension("first_items");

        @Test
        void a(Connection connection) {
            connection.scalar("select count(*) from first_items");
        }

        @Test
        void b(Connection connection) {
            connection.scalar("select count(*) from first_items");
        }

        @Test
        void c(Connection connection) {
            connection.scalar("select count(*) from first_items");
        }

        @Test
        void d(Connection connection) {
            connection.scalar("select count(*) from first_items");
        }
    }

    /** A fixture: four parallel tests on the second migration list. */
    @EnabledIf(ONLY_IN_KIT)
    @Execution(ExecutionMode.CONCURRENT)
    static class SecondList {
        @RegisterExtension
        static final RefreshDatabase DB = extension("second_items");

        @Test
        void a(Connection connection) {
            connection.scalar("select count(*) from second_items");
        }

        @Test
        void b(Connection connection) {
            connection.scalar("select count(*) from second_items");
        }

        @Test
        void c(Connection connection) {
            connection.scalar("select count(*) from second_items");
        }

        @Test
        void d(Connection connection) {
            connection.scalar("select count(*) from second_items");
        }
    }
}
