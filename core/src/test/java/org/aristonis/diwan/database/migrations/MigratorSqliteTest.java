package org.aristonis.diwan.database.migrations;

import org.aristonis.diwan.database.exception.MigrationFailedException;
import org.aristonis.diwan.database.exception.MissingMigrationsException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.UniqueConstraintViolationException;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class MigratorSqliteTest {

    @TempDir
    Path folder;

    private Connection db;
    private Schema schema;

    @BeforeEach
    void createDatabase() {
        db = connection("main.db");
        schema = SQLiteSchema.of(db);
    }

    private Connection connection(String file) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve(file));
        return Connection.builder().name(file).dataSource(dataSource).build();
    }

    /** A migration built from lambdas, with Laravel's defaults unless changed. */
    static final class TestMigration extends Migration {
        private final String name;
        private final Consumer<Schema> up;
        private final Consumer<Schema> down;
        private boolean isWithinTransaction = true;
        private boolean shouldRun = true;
        private String connection;

        TestMigration(String name, Consumer<Schema> up, Consumer<Schema> down) {
            this.name = name;
            this.up = up;
            this.down = down;
        }

        TestMigration withoutTransaction() {
            isWithinTransaction = false;
            return this;
        }

        TestMigration skipped() {
            shouldRun = false;
            return this;
        }

        TestMigration on(String name) {
            connection = name;
            return this;
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
            if (down != null) {
                down.accept(schema);
            }
        }

        @Override
        public boolean withinTransaction() {
            return isWithinTransaction;
        }

        @Override
        public boolean shouldRun() {
            return shouldRun;
        }

        @Override
        public Optional<String> connection() {
            return Optional.ofNullable(connection);
        }
    }

    static TestMigration createTable(String name, String table) {
        return new TestMigration(name, s -> s.create(table, t -> t.string("x")), s -> s.drop(table));
    }

    private static List<String> names(List<MigrationResult> results) {
        List<String> names = new ArrayList<>();
        for (MigrationResult result : results) {
            names.add(result.name() + ":" + result.outcome() + ":" + result.batch());
        }
        return names;
    }

    private Migrator migrator(Migration... migrations) {
        return Migrator.builder(schema).migrations(Arrays.asList(migrations)).build();
    }

    @Test
    void runsPendingMigrationsInNameOrderInOneBatchAndOnlyOnce() {
        Migrator migrator = migrator(createTable("2026_03_c", "c"), createTable("2026_01_a", "a"),
                createTable("2026_02_b", "b"));

        List<MigrationResult> first = migrator.run();
        List<MigrationResult> second = migrator.run();

        assertEquals(Arrays.asList("2026_01_a:RAN:1", "2026_02_b:RAN:1", "2026_03_c:RAN:1"), names(first));
        assertEquals(Collections.emptyList(), second);
        assertTrue(schema.hasTable("a") && schema.hasTable("b") && schema.hasTable("c"));
        assertEquals(Arrays.asList("2026_01_a", "2026_02_b", "2026_03_c"), migrator.repository().getRan());
    }

    @Test
    void stepGivesEachMigrationItsOwnBatchSkippedOnesIncluded() {
        Migrator migrator = migrator(createTable("a", "a"), createTable("b", "b").skipped(), createTable("c", "c"));

        List<MigrationResult> results = migrator.run(true);

        assertEquals(Arrays.asList("a:RAN:1", "b:SKIPPED:0", "c:RAN:3"), names(results));
        assertFalse(schema.hasTable("b"));
        assertEquals(Arrays.asList("a", "c"), migrator.repository().getRan());
    }

    @Test
    void aFailingMigrationIsUndoneAndStopsTheRun() {
        Migrator migrator = migrator(createTable("a", "a"),
                new TestMigration("b", s -> {
                    s.create("b", t -> t.string("x"));
                    s.connection().statement("insert into nowhere values (1)");
                }, null),
                createTable("c", "c"));

        MigrationFailedException failure = assertThrows(MigrationFailedException.class, migrator::run);

        assertEquals("b", failure.migration());
        assertEquals(MigrationFailedException.Direction.UP, failure.direction());
        assertEquals(Arrays.asList("a:RAN:1"), names(failure.completed()));
        assertTrue(schema.hasTable("a"));
        assertFalse(schema.hasTable("b"), "rolled back with its transaction");
        assertFalse(schema.hasTable("c"), "never ran");
        assertEquals(Collections.singletonList("a"), migrator.repository().getRan());
    }

    @Test
    void aMigrationWithoutATransactionKeepsWhatRanBeforeItFailed() {
        Migrator migrator = migrator(new TestMigration("a", s -> {
            s.create("a", t -> t.string("x"));
            s.connection().statement("insert into nowhere values (1)");
        }, null).withoutTransaction());

        assertThrows(MigrationFailedException.class, migrator::run);

        assertTrue(schema.hasTable("a"));
        assertEquals(Collections.emptyList(), migrator.repository().getRan());
    }

    @Test
    void aFailedRecordUndoesTheChangeItBelongsTo() {
        Migrator migrator = migrator(new TestMigration("a", s -> {
            s.create("a", t -> t.string("x"));
            s.connection().insert("insert into migrations (migration, batch) values (?, ?)", "a", 1);
        }, null));
        migrator.repository().createRepository();

        MigrationFailedException failure = assertThrows(MigrationFailedException.class, migrator::run);

        assertTrue(failure.getCause() instanceof UniqueConstraintViolationException);
        assertFalse(schema.hasTable("a"));
        assertEquals(Collections.emptyList(), migrator.repository().getRan());
    }

    @Test
    void rollsBackTheLastBatchByStepsByBatchAndAll() {
        Migrator migrator = migrator(createTable("a", "a"), createTable("b", "b"), createTable("c", "c"),
                createTable("d", "d"));
        Migrator first = migrator(createTable("a", "a"), createTable("b", "b"));
        first.run();
        migrator.run();

        assertEquals(Arrays.asList("d:ROLLED_BACK:2", "c:ROLLED_BACK:2"), names(migrator.rollback()));
        assertFalse(schema.hasTable("c"));
        migrator.run();
        assertEquals(Arrays.asList("d:ROLLED_BACK:2", "c:ROLLED_BACK:2", "b:ROLLED_BACK:1"),
                names(migrator.rollbackSteps(3)));
        migrator.run();
        assertEquals(Arrays.asList("a:ROLLED_BACK:1"), names(migrator.rollbackBatch(1)));
        assertEquals(Arrays.asList("d:ROLLED_BACK:2", "c:ROLLED_BACK:2", "b:ROLLED_BACK:2"), names(migrator.reset()));
        assertEquals(Collections.emptyList(), migrator.repository().getRan());
        assertFalse(schema.hasTable("a") || schema.hasTable("b") || schema.hasTable("d"));
        assertEquals(Collections.emptyList(), migrator.rollback());
        assertEquals(Collections.emptyList(), migrator.reset());
    }

    @Test
    void rollbackWithoutATableOrWithoutDownDoesNothingHarmful() {
        Migrator migrator = migrator(new TestMigration("a", s -> s.create("a", t -> t.string("x")), null));

        assertEquals(Collections.emptyList(), migrator.rollback());
        assertEquals(Collections.emptyList(), migrator.rollbackSteps(1));
        assertEquals(Collections.emptyList(), migrator.rollbackBatch(1));
        assertEquals(Collections.emptyList(), migrator.reset());
        migrator.run();
        assertEquals(Arrays.asList("a:ROLLED_BACK:1"), names(migrator.rollback()));
        assertTrue(schema.hasTable("a"), "no down step");
        assertEquals(Collections.emptyList(), migrator.repository().getRan());
    }

    @Test
    void entriesWithoutAMigrationAreReportedAndKept() {
        migrator(createTable("old", "old"), createTable("a", "a")).run();
        Migrator migrator = Migrator.builder(schema).add(createTable("a", "a")).add(createTable("b", "b"))
                .allowMissingOnRollback().build();

        List<MigrationStatus> status = migrator.status();
        List<MigrationResult> rolledBack = migrator.rollback();

        assertEquals("[MigrationStatus[a, RAN, batch 1], MigrationStatus[b, PENDING, batch 0],"
                + " MigrationStatus[old, MISSING, batch 1]]", status.toString());
        assertEquals(Arrays.asList("old:NOT_FOUND:1", "a:ROLLED_BACK:1"), names(rolledBack));
        assertEquals(Collections.singletonList("old"), migrator.repository().getRan());
    }

    @Test
    void statusWithoutATableListsEverythingAsPending() {
        assertEquals("[MigrationStatus[a, PENDING, batch 0]]", migrator(createTable("a", "a")).status().toString());
    }

    @Test
    void downFailuresAreReportedWithTheirDirection() {
        Migrator migrator = migrator(new TestMigration("a", s -> s.create("a", t -> t.string("x")),
                s -> s.drop("missing")));
        migrator.run();

        MigrationFailedException failure = assertThrows(MigrationFailedException.class, migrator::rollback);

        assertEquals(MigrationFailedException.Direction.DOWN, failure.direction());
        assertEquals(Collections.singletonList("a"), migrator.repository().getRan());
    }

    @Test
    void pretendListsTheSqlAndChangesNothing() {
        Migrator migrator = migrator(createTable("a", "a"), createTable("b", "b").skipped());

        List<MigrationResult> plan = migrator.pretendRun();

        assertEquals(Arrays.asList("a:PRETENDED:0", "b:SKIPPED:0"), names(plan));
        assertEquals("create table \"a\" (\"x\" varchar not null)", plan.get(0).queries().get(0).sql());
        assertFalse(migrator.repository().repositoryExists(), "pretend never creates the table");
        assertFalse(schema.hasTable("a"));

        migrator.run();
        List<MigrationResult> rollbackPlan = migrator.pretendRollback();
        List<MigrationResult> resetPlan = migrator.pretendReset();
        assertEquals("drop table \"a\"", rollbackPlan.get(0).queries().get(0).sql());
        assertEquals(Arrays.asList("a:PRETENDED:0"), names(resetPlan));
        assertTrue(schema.hasTable("a"));
        assertEquals(Arrays.asList("b:SKIPPED:0"), names(migrator.pretendRun()), "a skipped migration stays pending");
    }

    @Test
    void pretendWithoutATableRollsBackNothing() {
        Migrator migrator = migrator(createTable("a", "a"));

        assertEquals(Collections.emptyList(), migrator.pretendRollback());
        assertEquals(Collections.emptyList(), migrator.pretendReset());
    }

    @Test
    void aNamedConnectionRunsItsMigrationAndTheRecordStaysOnTheDefault() {
        Connection reports = connection("reports.db");
        Migrator migrator = Migrator.builder(schema).connection("reports", SQLiteSchema.of(reports))
                .add(createTable("a", "a").on("reports")).add(createTable("b", "b")).build();

        migrator.run();

        assertTrue(SQLiteSchema.of(reports).hasTable("a"));
        assertFalse(schema.hasTable("a"));
        assertFalse(SQLiteSchema.of(reports).hasTable("migrations"));
        assertEquals(Arrays.asList("a", "b"), migrator.repository().getRan());
        List<Map<String, Object>> plan = new ArrayList<>();
        for (MigrationResult result : migrator.pretendRollback()) {
            plan.add(Collections.<String, Object>singletonMap(result.name(), result.queries().get(0).sql()));
        }
        assertEquals(2, plan.size());
        migrator.reset();
        assertFalse(SQLiteSchema.of(reports).hasTable("a"));
    }

    @Test
    void theLockIsHeldAroundEveryChangingCallAndReleasedOnFailure() {
        List<String> calls = new ArrayList<>();
        MigrationLock lock = new MigrationLock() {
            @Override
            public void acquire() {
                calls.add("acquire");
            }

            @Override
            public void release() {
                calls.add("release");
                throw new IllegalStateException("release failed");
            }
        };
        Migrator migrator = Migrator.builder(schema).lock(lock)
                .add(new TestMigration("a", s -> s.connection().statement("insert into nowhere values (1)"), null))
                .build();

        MigrationFailedException failure = assertThrows(MigrationFailedException.class, migrator::run);
        migrator.status();
        migrator.pretendRun().size();

        assertEquals(Arrays.asList("acquire", "release"), calls);
        assertEquals("release failed", failure.getSuppressed()[0].getMessage());
        assertThrows(IllegalStateException.class, migrator::reset, "a release failure after success surfaces");
    }

    @Test
    void aRecordThatFailsAfterAnotherConnectionCommittedIsReportedAsSuch() {
        Connection reports = connection("reports.db");
        Migrator migrator = Migrator.builder(schema).connection("reports", SQLiteSchema.of(reports))
                .add(new TestMigration("a", s -> {
                    s.create("a", t -> t.string("x"));
                    db.statement("drop table migrations");
                }, null).on("reports"))
                .build();

        MigrationFailedException failure = assertThrows(MigrationFailedException.class, migrator::run);

        assertEquals(MigrationFailedException.Phase.RECORD, failure.phase());
        assertTrue(SQLiteSchema.of(reports).hasTable("a"), "the change was applied");
    }

    @Test
    void aMigrationThatThrowsInPretendIsReportedLikeARealRun() {
        Migrator migrator = migrator(new TestMigration("a", s -> {
            throw new IllegalStateException("boom");
        }, null));

        MigrationFailedException failure = assertThrows(MigrationFailedException.class, migrator::pretendRun);

        assertEquals("a", failure.migration());
        assertEquals(MigrationFailedException.Phase.MIGRATION, failure.phase());
    }

    @Test
    void olderCodeCannotRollBackANewerBatch() {
        migrator(createTable("a", "a"), createTable("b", "b")).run();
        Migrator older = migrator(createTable("a", "a"));

        MissingMigrationsException refused = assertThrows(MissingMigrationsException.class, older::rollback);
        assertThrows(MissingMigrationsException.class, older::reset);
        assertThrows(MissingMigrationsException.class, () -> older.rollbackSteps(2));
        assertThrows(MissingMigrationsException.class, () -> older.rollbackBatch(1));

        assertEquals(Collections.singletonList("b"), refused.names());
        assertTrue(refused.getMessage().contains("nothing was rolled back"));
        assertTrue(schema.hasTable("a") && schema.hasTable("b"), "nothing undone");
        assertEquals(Arrays.asList("a", "b"), older.repository().getRan());
        assertEquals(Arrays.asList("b:NOT_FOUND:1", "a:PRETENDED:0"), names(older.pretendRollback()));
    }

    @Test
    void aHostileRecordNameIsShownSafely() {
        migrator(createTable("a", "a")).run();
        db.insert("insert into migrations (migration, batch) values (?, ?)", "evil\u2028line", 1);

        MissingMigrationsException refused = assertThrows(MissingMigrationsException.class,
                () -> migrator(createTable("a", "a")).rollback());

        assertTrue(refused.getMessage().contains("evil?line"));
        assertEquals("evil\u2028line", refused.names().get(0));
    }

    @Test
    void aCorruptRecordFailsLoudlyInsteadOfBeingUndone() {
        migrator(createTable("a", "a")).run();
        db.insert("insert into migrations (migration, batch) values (?, ?)", "evil\nline", 1);

        assertThrows(IllegalArgumentException.class, () -> migrator(createTable("a", "a")).rollback());
        assertTrue(schema.hasTable("a"));
    }
}
