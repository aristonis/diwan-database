package org.aristonis.diwan.database.migrations;

import org.aristonis.diwan.database.exception.MigrationFailedException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.schema.grammar.SchemaGrammar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class MigratorBuildTest {

    static Migration named(String name, String connection) {
        return new Migration() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void up(Schema schema) {
            }

            @Override
            public Optional<String> connection() {
                return Optional.ofNullable(connection);
            }
        };
    }

    @TempDir
    Path folder;

    private Schema schema;

    @BeforeEach
    void createSchema() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("test.db"));
        schema = Schema.of(Connection.builder().name("main").dataSource(dataSource).build(), new SchemaGrammar() { });
    }

    @Test
    void sortsMigrationsByName() {
        Migrator migrator = Migrator.builder(schema)
                .migrations(Arrays.asList(named("2026_b", null), named("2026_a", null))).add(named("2025_z", null))
                .build();

        assertEquals("2025_z", migrator.migrations().get(0).name());
        assertEquals("2026_b", migrator.migrations().get(2).name());
        assertEquals("migrations", migrator.repository().baseTable());
    }

    @Test
    void refusesBadListsBeforeAnythingRuns() {
        assertThrows(IllegalArgumentException.class,
                () -> Migrator.builder(schema).add(named("a", null)).add(named("a", null)).build());
        assertThrows(IllegalArgumentException.class, () -> Migrator.builder(schema).add(named(" ", null)).build());
        assertThrows(IllegalArgumentException.class, () -> Migrator.builder(schema).add(named("a", "reports")).build());
        assertThrows(IllegalArgumentException.class, () -> Migrator.builder(schema).table("Bad Name").build());
        assertThrows(IllegalArgumentException.class, () -> Migrator.builder(schema).connection(" ", schema));
        assertThrows(IllegalArgumentException.class,
                () -> Migrator.builder(schema).connection("r", schema).connection("r", schema));
        assertThrows(NullPointerException.class, () -> Migrator.builder(null));
        assertThrows(NullPointerException.class, () -> Migrator.builder(schema).add(null));
        assertThrows(NullPointerException.class,
                () -> Migrator.builder(schema).migrations(Collections.<Migration>singletonList(null)));
        assertThrows(NullPointerException.class, () -> Migrator.builder(schema).table(null));
        assertThrows(NullPointerException.class, () -> Migrator.builder(schema).lock(null));
        assertThrows(IllegalArgumentException.class, () -> Migrator.builder(schema).build().rollbackSteps(0));
    }

    @Test
    void acceptsAMigrationOnAKnownConnection() {
        Migrator migrator = Migrator.builder(schema).connection("reports", schema).add(named("a", "reports")).build();

        assertEquals(1, migrator.migrations().size());
    }

    @Test
    void refusesToRunInsideAnOpenTransaction() {
        Migrator migrator = Migrator.builder(schema).add(named("a", null)).build();

        schema.connection().transaction(tx -> {
            Migrator inside = Migrator.builder(Schema.of(tx, schema.grammar())).build();
            assertThrows(IllegalStateException.class, inside::run);
            assertThrows(IllegalStateException.class, inside::rollback);
            assertThrows(IllegalStateException.class, inside::reset);
            assertThrows(IllegalStateException.class, inside::pretendRun);
            assertThrows(IllegalStateException.class, () -> Migrator.builder(schema).connection("tx",
                    Schema.of(tx, schema.grammar())).build().rollbackBatch(1));
            return null;
        });
        assertEquals(1, migrator.migrations().size());
    }

    @Test
    void resultsAndStatusesDescribeThemselves() {
        MigrationResult result = new MigrationResult("a", MigrationResult.Outcome.RAN, 1,
                Collections.emptyList());
        MigrationStatus status = new MigrationStatus("a", MigrationStatus.State.PENDING, 0);
        MigrationFailedException failure = new MigrationFailedException("b", MigrationFailedException.Direction.DOWN,
                MigrationFailedException.Phase.MIGRATION, Collections.singletonList(result), new IllegalStateException("x"));
        MigrationFailedException recording = new MigrationFailedException("c", MigrationFailedException.Direction.UP,
                MigrationFailedException.Phase.RECORD, Collections.<MigrationResult>emptyList(), new IllegalStateException());

        assertEquals("MigrationResult[a, RAN, batch 1]", result.toString());
        assertEquals("MigrationStatus[a, PENDING, batch 0]", status.toString());
        assertEquals("Migration b failed while running down", failure.getMessage());
        assertEquals("b", failure.migration());
        assertEquals(MigrationFailedException.Direction.DOWN, failure.direction());
        assertEquals(Collections.singletonList(result), failure.completed());
        assertEquals(MigrationFailedException.Phase.MIGRATION, failure.phase());
        assertEquals("Migration c failed while recording up", recording.getMessage());
    }
}
