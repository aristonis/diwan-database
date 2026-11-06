package org.aristonis.diwan.database.migrations;

import org.aristonis.diwan.database.exception.MigrationFailedException;
import org.aristonis.diwan.database.migrations.event.MigrationEvent;
import org.aristonis.diwan.database.migrations.event.MigrationListener;
import org.aristonis.diwan.database.migrations.event.MigrationsEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.MigrationFailedException.Direction;
import org.aristonis.diwan.database.migrations.MigratorSqliteTest.TestMigration;
import org.aristonis.diwan.database.exception.ForeignKeyCheckException;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** What a {@link MigrationListener} sees and what happens when it throws. */
class MigrationEventsTest {

    @TempDir
    Path folder;

    private Connection db;
    private Schema schema;
    private Migrator migrator;
    private final List<String> log = new ArrayList<>();

    /** Writes every event to the log as one short line. */
    private final MigrationListener recorder = new MigrationListener() {
        @Override
        public void migrationsStarted(MigrationsEvent event) {
            log.add("started " + event.direction() + (event.isPretend() ? " pretend" : "") + " on "
                    + event.connectionName());
        }

        @Override
        public void migrationsEnded(MigrationsEvent event) {
            log.add("ended " + event.direction() + (event.isPretend() ? " pretend" : ""));
        }

        @Override
        public void noPendingMigrations(MigrationsEvent event) {
            log.add("none " + event.direction() + (event.isPretend() ? " pretend" : ""));
        }

        @Override
        public void migrationStarted(MigrationEvent event) {
            log.add("start " + describe(event));
        }

        @Override
        public void migrationEnded(MigrationEvent event) {
            log.add("end " + describe(event));
        }

        @Override
        public void migrationSkipped(MigrationEvent event) {
            log.add("skip " + describe(event));
        }

        @Override
        public void migrationFailed(MigrationEvent event, Throwable failure) {
            log.add("fail " + describe(event));
        }
    };

    private static String describe(MigrationEvent event) {
        return event.name() + " " + event.direction() + " b" + event.batch() + (event.isPretend() ? " pretend" : "");
    }

    @BeforeEach
    void createDatabase() {
        db = connection("main.db", false);
        schema = SQLiteSchema.of(db);
    }

    private Connection connection(String file, boolean isEnforcingKeys) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve(file));
        dataSource.setEnforceForeignKeys(isEnforcingKeys);
        return Connection.builder().name(file).dataSource(dataSource).build();
    }

    private Migrator migrator(MigrationListener listener, Migration... migrations) {
        migrator = Migrator.builder(schema).migrations(Arrays.asList(migrations)).listener(listener).build();
        return migrator;
    }

    private static TestMigration table(String name) {
        return MigratorSqliteTest.createTable(name, name);
    }

    private static TestMigration failing(String name) {
        return new TestMigration(name, s -> s.connection().statement("insert into nowhere values (1)"), null);
    }

    @Test
    void aRunReportsStartedThenEachMigrationThenEnded() {
        migrator(recorder, table("a"), table("b")).run();

        assertEquals(Arrays.asList("started UP on main.db", "start a UP b1", "end a UP b1", "start b UP b1",
                "end b UP b1", "ended UP"), log);
    }

    @Test
    void stepGivesEachEventItsOwnBatchAndASkippedMigrationBatchZero() {
        migrator(recorder, table("a"), table("b").skipped(), table("c")).run(true);

        assertEquals(Arrays.asList("started UP on main.db", "start a UP b1", "end a UP b1", "skip b UP b0",
                "start c UP b3", "end c UP b3", "ended UP"), log);
    }

    @Test
    void nothingPendingReportsOnlyNoPendingMigrations() {
        migrator(recorder, table("a")).run();
        log.clear();

        migrator.run();

        assertEquals(Collections.singletonList("none UP"), log);
    }

    @Test
    void aFailingMigrationReportsFailedThenThrowsAndNeverEnds() {
        migrator(recorder, table("a"), failing("b"), table("c"));

        MigrationFailedException failure = assertThrows(MigrationFailedException.class, migrator::run);

        assertEquals("b", failure.migration());
        assertEquals(Arrays.asList("started UP on main.db", "start a UP b1", "end a UP b1", "start b UP b1",
                "fail b UP b1"), log);
    }

    private static TestMigration throwing(String name) {
        return new TestMigration(name, s -> {
            throw new IllegalStateException("broken");
        }, null);
    }

    @Test
    void theFailedListenerGetsTheExceptionThatIsThrown() {
        List<Throwable> seen = new ArrayList<>();
        migrator(new MigrationListener() {
            @Override
            public void migrationFailed(MigrationEvent event, Throwable failure) {
                seen.add(failure);
            }
        }, failing("a"));

        MigrationFailedException thrown = assertThrows(MigrationFailedException.class, migrator::run);

        assertEquals(1, seen.size());
        assertSame(thrown, seen.get(0));
    }

    @Test
    void rollbackAndResetReportTheDownDirectionWithEachEntrysBatch() {
        migrator(recorder, table("a"), table("b")).run(true);
        log.clear();

        migrator.rollback();
        migrator.reset();

        assertEquals(Arrays.asList("started DOWN on main.db", "start b DOWN b2", "end b DOWN b2", "ended DOWN",
                "started DOWN on main.db", "start a DOWN b1", "end a DOWN b1", "ended DOWN"), log);
    }

    @Test
    void rollbackStepsAndBatchAlsoReportDownEvents() {
        migrator(recorder, table("a"), table("b")).run(true);
        log.clear();

        migrator.rollbackSteps(1);
        migrator.rollbackBatch(1);

        assertEquals(Arrays.asList("started DOWN on main.db", "start b DOWN b2", "end b DOWN b2", "ended DOWN",
                "started DOWN on main.db", "start a DOWN b1", "end a DOWN b1", "ended DOWN"), log);
    }

    @Test
    void rollbackWithNothingRecordedReportsNoPendingEvenWithoutAMigrationsTable() {
        migrator(recorder, table("a"));

        migrator.rollback();
        migrator.reset();

        assertEquals(Arrays.asList("none DOWN", "none DOWN"), log);
    }

    @Test
    void aMigrationTheMigratorDoesNotHaveIsReportedWithoutEvents() {
        migrator(recorder, table("a"), table("b")).run();
        Migrator partial = Migrator.builder(schema).add(table("a")).listener(recorder).allowMissingOnRollback().build();
        log.clear();

        partial.reset();

        assertEquals(Arrays.asList("started DOWN on main.db", "start a DOWN b1", "end a DOWN b1", "ended DOWN"), log);
    }

    @Test
    void pretendEventsAreFlaggedAndNothingChanges() {
        migrator(recorder, table("a"), table("b").skipped());

        migrator.pretendRun();

        assertEquals(Arrays.asList("started UP pretend on main.db", "start a UP b0 pretend", "end a UP b0 pretend",
                "skip b UP b0 pretend", "ended UP pretend"), log);
        assertFalse(schema.hasTable("a"));
    }

    @Test
    void pretendRollbackAndResetReportDownEventsFlagged() {
        migrator(recorder, table("a")).run();
        log.clear();

        migrator.pretendRollback();
        migrator.pretendReset();

        assertEquals(Arrays.asList("started DOWN pretend on main.db", "start a DOWN b1 pretend",
                "end a DOWN b1 pretend", "ended DOWN pretend", "started DOWN pretend on main.db",
                "start a DOWN b1 pretend", "end a DOWN b1 pretend", "ended DOWN pretend"), log);
        assertTrue(schema.hasTable("a"));
    }

    @Test
    void pretendWithNothingPendingReportsNoPendingFlagged() {
        migrator(recorder, table("a")).run();
        log.clear();

        migrator.pretendRun();

        assertEquals(Collections.singletonList("none UP pretend"), log);
    }

    @Test
    void aFailingPretendReportsFailedOutsideThePretendScope() {
        migrator(recorder, throwing("a"));

        assertThrows(MigrationFailedException.class, migrator::pretendRun);

        assertEquals(Arrays.asList("started UP pretend on main.db", "start a UP b0 pretend",
                "fail a UP b0 pretend"), log);
    }

    @Test
    void aListenerThatRunsSqlDuringAPretendEventDoesNotLeakIntoTheCollectedQueries() {
        migrator(new MigrationListener() {
            @Override
            public void migrationEnded(MigrationEvent event) {
                db.statement("create table probe (x integer)");
            }
        }, table("a"));

        List<MigrationResult> results = migrator.pretendRun();

        assertEquals(1, results.size());
        assertTrue(results.get(0).queries().stream().noneMatch(query -> query.sql().contains("probe")));
        assertTrue(schema.hasTable("probe"));
    }

    @Test
    void aStartedListenerThatThrowsStopsTheRunBeforeAnyWork() {
        IllegalStateException refusal = new IllegalStateException("no");
        migrator(new MigrationListener() {
            @Override
            public void migrationsStarted(MigrationsEvent event) {
                throw refusal;
            }
        }, table("a"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, migrator::run);

        assertSame(refusal, thrown);
        assertFalse(schema.hasTable("a"));
        assertEquals(Collections.emptyList(), migrator.repository().getRan());
    }

    @Test
    void aMigrationStartedListenerThatThrowsKeepsEarlierMigrationsAndSkipsItsOwn() {
        migrator(new MigrationListener() {
            @Override
            public void migrationStarted(MigrationEvent event) {
                if (event.name().equals("b")) {
                    throw new IllegalStateException("no");
                }
            }
        }, table("a"), table("b"));

        assertThrows(IllegalStateException.class, migrator::run);

        assertTrue(schema.hasTable("a"));
        assertFalse(schema.hasTable("b"));
        assertEquals(Collections.singletonList("a"), migrator.repository().getRan());
    }

    @Test
    void aStartedListenerThatThrowsOnRollbackUndoesNothing() {
        migrator(recorder, table("a")).run();
        Migrator guarded = Migrator.builder(schema).add(table("a")).listener(new MigrationListener() {
            @Override
            public void migrationsStarted(MigrationsEvent event) {
                throw new IllegalStateException("no");
            }
        }).build();

        assertThrows(IllegalStateException.class, guarded::rollback);

        assertTrue(schema.hasTable("a"));
        assertEquals(Collections.singletonList("a"), guarded.repository().getRan());
    }

    @Test
    void everyEventFiresInsideTheLock() {
        MigrationLock lock = new MigrationLock() {
            @Override
            public void acquire() {
                log.add("acquire");
            }

            @Override
            public void release() {
                log.add("release");
            }
        };
        Migrator locked = Migrator.builder(schema).add(table("a")).lock(lock).listener(recorder).build();

        locked.run();

        assertEquals(Arrays.asList("acquire", "started UP on main.db", "start a UP b1", "end a UP b1", "ended UP",
                "release"), log);
    }

    @Test
    void anEndedEventSeesTheMigrationAlreadyRecorded() {
        List<List<String>> ranWhenEnded = new ArrayList<>();
        migrator(new MigrationListener() {
            @Override
            public void migrationEnded(MigrationEvent event) {
                ranWhenEnded.add(migrator.repository().getRan());
            }
        }, table("a"), table("b"));

        migrator.run();

        assertEquals(Arrays.asList(Arrays.asList("a"), Arrays.asList("a", "b")), ranWhenEnded);
    }

    @Test
    void anEndedEventSeesTheRecordRemovedOnTheWayDown() {
        migrator(recorder, table("a")).run();
        List<List<String>> ranWhenEnded = new ArrayList<>();
        Migrator down = Migrator.builder(schema).add(table("a")).listener(new MigrationListener() {
            @Override
            public void migrationEnded(MigrationEvent event) {
                ranWhenEnded.add(migrator.repository().getRan());
            }
        }).build();

        down.rollback();

        assertEquals(Collections.singletonList(Collections.<String>emptyList()), ranWhenEnded);
    }

    @Test
    void anEndedListenerThatThrowsPropagatesAfterTheMigrationIsRecorded() {
        IllegalStateException refusal = new IllegalStateException("no");
        migrator(new MigrationListener() {
            @Override
            public void migrationEnded(MigrationEvent event) {
                throw refusal;
            }
        }, table("a"), table("b"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, migrator::run);

        assertSame(refusal, thrown);
        assertTrue(schema.hasTable("a"));
        assertEquals(Collections.singletonList("a"), migrator.repository().getRan());
        assertFalse(schema.hasTable("b"));
    }

    @Test
    void aSkippedListenerThatThrowsPropagatesAndEarlierWorkStaysRecorded() {
        migrator(new MigrationListener() {
            @Override
            public void migrationSkipped(MigrationEvent event) {
                throw new IllegalStateException("no");
            }
        }, table("a"), table("b").skipped(), table("c"));

        assertThrows(IllegalStateException.class, migrator::run);

        assertEquals(Collections.singletonList("a"), migrator.repository().getRan());
        assertFalse(schema.hasTable("c"));
    }

    @Test
    void anEndedListenerThatThrowsOnTheLastMigrationSkipsMigrationsEnded() {
        migrator(new MigrationListener() {
            @Override
            public void migrationEnded(MigrationEvent event) {
                log.add("end " + event.name());
                throw new IllegalStateException("no");
            }

            @Override
            public void migrationsEnded(MigrationsEvent event) {
                log.add("ended");
            }
        }, table("a"));

        assertThrows(IllegalStateException.class, migrator::run);

        assertEquals(Collections.singletonList("end a"), log);
    }

    @Test
    void aFailedListenerThatThrowsIsSuppressedOntoTheMigrationFailureAndOthersStillRun() {
        IllegalStateException noisy = new IllegalStateException("noisy");
        migrator = Migrator.builder(schema).add(failing("a")).listener(new MigrationListener() {
            @Override
            public void migrationFailed(MigrationEvent event, Throwable failure) {
                throw noisy;
            }
        }).listener(recorder).build();

        MigrationFailedException failure = assertThrows(MigrationFailedException.class, migrator::run);

        assertEquals(Collections.singletonList(noisy), Arrays.asList(failure.getSuppressed()));
        assertEquals("a", failure.migration());
        assertTrue(log.contains("fail a UP b1"));
    }

    @Test
    void aFailedListenerThatThrowsAnErrorIsSuppressedAndTheMigrationFailureStillPropagates() {
        AssertionError broken = new AssertionError("broken listener");
        migrator = Migrator.builder(schema).add(failing("a")).listener(new MigrationListener() {
            @Override
            public void migrationFailed(MigrationEvent event, Throwable failure) {
                throw broken;
            }
        }).listener(recorder).build();

        MigrationFailedException failure = assertThrows(MigrationFailedException.class, migrator::run);

        assertEquals(Collections.singletonList(broken), Arrays.asList(failure.getSuppressed()));
        assertTrue(log.contains("fail a UP b1"));
    }

    @Test
    void aSkippedMigrationReportsTheNameOfTheConnectionItRunsOn() {
        Connection other = connection("other.db", false);
        List<String> names = new ArrayList<>();
        Migrator multi = Migrator.builder(schema).connection("other", SQLiteSchema.of(other))
                .add(table("a").on("other").skipped()).add(table("b").skipped()).listener(new MigrationListener() {
                    @Override
                    public void migrationSkipped(MigrationEvent event) {
                        names.add(event.name() + ":" + event.connectionName());
                    }
                }).build();

        multi.run();

        assertEquals(Arrays.asList("a:other.db", "b:main.db"), names);
    }

    @Test
    void listenersAreCalledInRegistrationOrder() {
        List<String> order = new ArrayList<>();
        Migrator ordered = Migrator.builder(schema).add(table("a"))
                .listener(new MigrationListener() {
                    @Override
                    public void migrationsStarted(MigrationsEvent event) {
                        order.add("first");
                    }
                })
                .listener(new MigrationListener() {
                    @Override
                    public void migrationsStarted(MigrationsEvent event) {
                        order.add("second");
                    }
                }).build();

        ordered.run();

        assertEquals(Arrays.asList("first", "second"), order);
    }

    @Test
    void aRecordThatFailsAfterAMigrationReportsFailedWithTheRecordPhase() {
        TestMigration unlogged = new TestMigration("a", s -> {
            s.create("a", t -> t.string("x"));
            s.connection().insert("insert into migrations (migration, batch) values (?, ?)", "a", 1);
        }, null).withoutTransaction();
        migrator(recorder, unlogged);
        migrator.repository().createRepository();

        MigrationFailedException failure = assertThrows(MigrationFailedException.class, migrator::run);

        assertEquals(MigrationFailedException.Phase.RECORD, failure.phase());
        assertEquals(Arrays.asList("started UP on main.db", "start a UP b1", "fail a UP b1"), log);
    }

    @Test
    void aMigrationEventNamesTheConnectionTheMigrationRunsOn() {
        Connection other = connection("other.db", false);
        List<String> names = new ArrayList<>();
        Migrator multi = Migrator.builder(schema).connection("other", SQLiteSchema.of(other))
                .add(table("a").on("other")).listener(new MigrationListener() {
                    @Override
                    public void migrationsStarted(MigrationsEvent event) {
                        names.add("run:" + event.connectionName());
                    }

                    @Override
                    public void migrationStarted(MigrationEvent event) {
                        names.add("migration:" + event.connectionName());
                    }
                }).build();

        multi.run();

        assertEquals(Arrays.asList("run:main.db", "migration:other.db"), names);
    }

    @Test
    void directionFollowsTheCall() {
        List<Direction> seen = new ArrayList<>();
        migrator(new MigrationListener() {
            @Override
            public void migrationStarted(MigrationEvent event) {
                seen.add(event.direction());
            }
        }, table("a"));

        migrator.run();
        migrator.rollback();

        assertEquals(Arrays.asList(Direction.UP, Direction.DOWN), seen);
    }

    @Test
    void aListenerCannotBeNull() {
        assertThrows(NullPointerException.class, () -> Migrator.builder(schema).listener(null));
    }

    // Foreign-key rerun: a table rebuild needs enforcement off, so the first attempt is rolled back
    // and the migration runs again. That first attempt is not a failure.

    private final TestMigration users = new TestMigration("2026_01_users", s -> s.create("users", t -> {
        t.id();
        t.string("email").unique();
    }), s -> s.drop("users"));

    private final TestMigration posts = new TestMigration("2026_02_posts", s -> s.create("posts", t -> {
        t.id();
        t.foreignId("user_id").constrained().cascadeOnDelete();
        t.integer("category_id").nullable();
    }), s -> s.drop("posts"));

    private final TestMigration comments = new TestMigration("2026_03_comments", s -> s.create("comments", t -> {
        t.id();
        t.foreignId("post_id").constrained().cascadeOnDelete();
    }), s -> s.drop("comments"));

    private final TestMigration categories = new TestMigration("2026_04_categories", s -> {
        s.create("categories", t -> t.id());
        s.table("posts", t -> t.foreign("category_id").references("id").on("categories").nullOnDelete());
    }, s -> {
        s.table("posts", t -> t.dropForeign(Arrays.asList("category_id")));
        s.drop("categories");
    });

    private Connection keyedDatabase() {
        db = connection("keys.db", true);
        schema = SQLiteSchema.of(db);
        Migrator first = Migrator.builder(schema).migrations(Arrays.asList(users, posts, comments)).build();
        first.run();
        db.insert("insert into users (id, email) values (1, 'a'), (2, 'b')");
        db.insert("insert into posts (id, user_id) values (1, 1), (2, 2)");
        db.insert("insert into comments (post_id) values (1), (2)");
        return db;
    }

    @Test
    void theForeignKeysOffRerunReportsNoFailedEventForItsFirstAttempt() {
        keyedDatabase();
        migrator(recorder, users, posts, comments, categories);

        migrator.run();

        assertEquals(Arrays.asList("started UP on keys.db", "start 2026_04_categories UP b2",
                "end 2026_04_categories UP b2", "ended UP"), log);
        assertEquals(2, schema.foreignKeys("posts").size());
    }

    @Test
    void aRerunThatFailsReportsFailedExactlyOnce() {
        keyedDatabase();
        db.update("update posts set category_id = 99 where id = 2");
        migrator(recorder, users, posts, comments, categories);

        MigrationFailedException failure = assertThrows(MigrationFailedException.class, migrator::run);

        assertInstanceOf(ForeignKeyCheckException.class, failure.getCause());
        assertEquals(Arrays.asList("started UP on keys.db", "start 2026_04_categories UP b2",
                "fail 2026_04_categories UP b2"), log);
    }
}
