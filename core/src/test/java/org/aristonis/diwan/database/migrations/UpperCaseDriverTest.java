package org.aristonis.diwan.database.migrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.driver.Driver;
import org.aristonis.diwan.database.driver.DriverCapability;
import org.aristonis.diwan.database.driver.DriverInfo;
import org.aristonis.diwan.database.driver.DriverSession;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.schema.SchemaGrammar;
import org.junit.jupiter.api.Test;

/** A driver that reports labels in upper case and numbers as text, as some databases and cursors do. */
class UpperCaseDriverTest {

    private static final class ScriptedDriver implements Driver {
        private final List<Map<String, Object>> rows;

        ScriptedDriver(List<Map<String, Object>> rows) {
            this.rows = rows;
        }

        @Override
        public DriverSession open() {
            return new DriverSession() {
                @Override
                public List<Map<String, Object>> query(String sql, List<Object> bindings) {
                    return rows;
                }

                @Override
                public int update(String sql, List<Object> bindings) {
                    return 0;
                }

                @Override
                public boolean execute(String sql, List<Object> bindings) {
                    return false;
                }

                @Override
                public void executeUnprepared(String sql) {
                }

                @Override
                public boolean inExternalTransaction() {
                    return false;
                }

                @Override
                public void begin() {
                }

                @Override
                public void commit() {
                }

                @Override
                public void rollback() {
                }

                @Override
                public void savepoint(String name) {
                }

                @Override
                public void rollbackToSavepoint(String name) {
                }

                @Override
                public void releaseSavepoint(String name) {
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public DriverInfo info() {
            return new DriverInfo("Oracle", "23", EnumSet.noneOf(DriverCapability.class));
        }
    }

    private static Map<String, Object> row(Object name, Object batch) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("MIGRATION", name);
        row.put("BATCH", batch);
        row.put("CHECKSUM", null);
        return row;
    }

    private static MigrationRepository repository(List<Map<String, Object>> rows) {
        Connection db = Connection.builder().name("scripted").driver(new ScriptedDriver(rows)).build();
        return new DatabaseMigrationRepository(Schema.of(db, new SchemaGrammar() { }));
    }

    @Test
    void readsColumnsByPositionAndBatchesGivenAsText() {
        MigrationRepository repository = repository(Arrays.asList(row("a", "1"), row("b", 2L)));

        assertEquals(Arrays.asList("a", "b"), repository.getRan());
        assertEquals(Arrays.asList(new MigrationEntry("a", 1), new MigrationEntry("b", 2)), repository.getLast());
        assertEquals(Integer.valueOf(1), repository.getMigrationBatches().get("a"));
    }

    @Test
    void unreadableValuesFailWithAClearError() {
        assertThrows(IllegalStateException.class, () -> repository(Collections.singletonList(row(null, 1))).getRan());
        assertThrows(IllegalStateException.class, () -> repository(Collections.singletonList(row("a", "x"))).getLast());
        assertThrows(IllegalStateException.class,
                () -> repository(Collections.singletonList(row("a", new Object()))).getLast());
        Map<String, Object> oneColumn = new LinkedHashMap<>();
        oneColumn.put("MIGRATION", "a");
        assertThrows(IllegalStateException.class, () -> repository(Collections.singletonList(oneColumn)).getLast());
    }

    @Test
    void tableCheckIsRefusedByAGrammarWithoutIt() {
        assertThrows(UnsupportedOperationException.class,
                () -> repository(Collections.<Map<String, Object>>emptyList()).repositoryExists());
    }
}
