package org.aristonis.diwan.database.migrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.exception.UniqueConstraintViolationException;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class DatabaseMigrationRepositorySqliteTest {

    @TempDir
    Path folder;

    private Connection db;
    private DatabaseMigrationRepository repository;

    @BeforeEach
    void createDatabase() {
        db = connection("");
        repository = new DatabaseMigrationRepository(SQLiteSchema.of(db));
    }

    private Connection connection(String prefix) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("test.db"));
        return Connection.builder().name("sqlite").tablePrefix(prefix).dataSource(dataSource).build();
    }

    @Test
    void createsChecksAndDropsTheTable() {
        assertFalse(repository.repositoryExists());

        repository.createRepository();
        assertTrue(repository.repositoryExists());

        repository.deleteRepository();
        assertFalse(repository.repositoryExists());
    }

    @Test
    void readingBeforeTheTableExistsIsAQueryException() {
        assertThrows(QueryException.class, repository::getRan);
    }

    @Test
    void emptyTableStartsAtBatchOne() {
        repository.createRepository();

        assertEquals(0, repository.getLastBatchNumber());
        assertEquals(1, repository.getNextBatchNumber());
        assertEquals(Collections.emptyList(), repository.getLast());
        assertEquals(Collections.emptyList(), repository.getRan());
    }

    @Test
    void readsComeBackInLaravelsOrder() {
        repository.createRepository();
        repository.log("2026_01_02_b", 2, null);
        repository.log("2026_01_01_b", 1, null);
        repository.log("2026_01_02_a", 2, null);
        repository.log("2026_01_01_a", 1, null);

        assertEquals(Arrays.asList("2026_01_01_a", "2026_01_01_b", "2026_01_02_a", "2026_01_02_b"),
                repository.getRan());
        assertEquals(Arrays.asList(new MigrationEntry("2026_01_02_b", 2), new MigrationEntry("2026_01_02_a", 2),
                new MigrationEntry("2026_01_01_b", 1)), repository.getMigrations(3));
        assertEquals(Arrays.asList(new MigrationEntry("2026_01_01_b", 1), new MigrationEntry("2026_01_01_a", 1)),
                repository.getMigrationsByBatch(1));
        assertEquals(Arrays.asList(new MigrationEntry("2026_01_02_b", 2), new MigrationEntry("2026_01_02_a", 2)),
                repository.getLast());
        Map<String, Integer> batches = repository.getMigrationBatches();
        assertEquals(Arrays.asList("2026_01_01_a", "2026_01_01_b", "2026_01_02_a", "2026_01_02_b"),
                Arrays.asList(batches.keySet().toArray()));
        assertEquals(Integer.valueOf(2), batches.get("2026_01_02_a"));
        assertEquals(2, repository.getLastBatchNumber());
        assertEquals(3, repository.getNextBatchNumber());
    }

    @Test
    void returnedListsCannotBeChanged() {
        repository.createRepository();
        repository.log("a", 1, null);

        List<String> ran = repository.getRan();
        List<MigrationEntry> last = repository.getLast();
        Map<String, Integer> batches = repository.getMigrationBatches();

        assertThrows(UnsupportedOperationException.class, () -> ran.add("b"));
        assertThrows(UnsupportedOperationException.class, () -> last.clear());
        assertThrows(UnsupportedOperationException.class, () -> batches.put("b", 1));
    }

    @Test
    void loggingTwiceIsAUniqueViolationAndDeletingAnAbsentNameIsANoOp() {
        repository.createRepository();
        repository.log("a", 1, null);

        assertThrows(UniqueConstraintViolationException.class, () -> repository.log("a", 2, null));

        repository.delete("missing");
        repository.delete("a");
        assertEquals(Collections.emptyList(), repository.getRan());
    }

    @Test
    void refusesBadArgumentsBeforeTheDatabase() {
        assertThrows(IllegalArgumentException.class, () -> repository.getMigrations(0));
        assertThrows(IllegalArgumentException.class, () -> repository.log(" ", 1, null));
        assertThrows(IllegalArgumentException.class, () -> repository.log("a", 0, null));
        assertThrows(NullPointerException.class, () -> repository.delete(null));
        assertThrows(NullPointerException.class, () -> new DatabaseMigrationRepository((org.aristonis.diwan.database.schema.Schema) null));
        assertThrows(NullPointerException.class, () -> new DatabaseMigrationRepository(SQLiteSchema.of(db), null));
        assertThrows(IllegalArgumentException.class, () -> new DatabaseMigrationRepository(SQLiteSchema.of(db), "x; drop table y"));
    }

    @Test
    void addsTheConnectionsTablePrefix() {
        DatabaseMigrationRepository prefixed = new DatabaseMigrationRepository(SQLiteSchema.of(connection("app_")), "runs");

        prefixed.createRepository();

        assertEquals("app_runs", prefixed.table());
        assertTrue(prefixed.repositoryExists());
        assertFalse(repository.repositoryExists());
        assertThrows(IllegalArgumentException.class,
                () -> new DatabaseMigrationRepository(SQLiteSchema.of(connection("App_")), "runs"));
    }

    @Test
    void pretendModeReachesNoDatabase() {
        repository.createRepository();
        repository.log("a", 1, null);

        List<?> recorded = db.pretend(pretend -> {
            DatabaseMigrationRepository inPretend = new DatabaseMigrationRepository(SQLiteSchema.of(pretend));
            assertEquals(0, inPretend.getLastBatchNumber());
            assertFalse(inPretend.repositoryExists());
            inPretend.log("b", 2, null);
        });

        assertEquals(3, recorded.size());
        assertEquals(Collections.singletonList("a"), repository.getRan());
    }
}
