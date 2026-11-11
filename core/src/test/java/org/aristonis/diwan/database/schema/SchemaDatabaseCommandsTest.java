package org.aristonis.diwan.database.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.LoggedQuery;
import org.aristonis.diwan.database.schema.grammar.SchemaGrammar;
import org.aristonis.diwan.database.schema.grammar.SchemaQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** Checks the database commands of {@link Schema} and the hooks of {@link SchemaGrammar}. */
class SchemaDatabaseCommandsTest {

    /** Writes recognisable SQL for the database commands; the exists query answers by name. */
    private static class DatabaseGrammar extends SchemaGrammar {
        @Override
        public String compileCreateDatabase(Connection connection, String name, String template,
                CopyStrategy strategy) {
            return "make " + name + " from " + template + " by " + strategy;
        }

        @Override
        public String compileDropDatabaseIfExists(Connection connection, String name, boolean force) {
            return "select 'drop " + name + " force " + force + "'";
        }

        @Override
        public SchemaQuery compileDatabaseExists(String name) {
            String sql = "present".equals(name) ? "select 1" : "select 1 where 0";
            return new SchemaQuery(sql, Collections.emptyList());
        }

        @Override
        public boolean supportsCopyStrategy(Connection connection) {
            return true;
        }
    }

    @TempDir
    Path folder;

    private Connection db;

    @BeforeEach
    void openDatabase() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("test.db"));
        db = Connection.builder().name("sqlite").dataSource(dataSource).build();
    }

    private List<String> pretended(SchemaGrammar grammar, Consumer<Schema> work) {
        List<String> statements = new ArrayList<>();
        for (LoggedQuery query : Schema.of(db, grammar).pretend(work)) {
            statements.add(query.sql());
        }
        return statements;
    }

    @Test
    void baseGrammarRefusesEveryDatabaseCommand() {
        SchemaGrammar base = new SchemaGrammar() { };

        assertThrows(UnsupportedOperationException.class,
                () -> base.compileCreateDatabase(db, "a", null, null));
        assertThrows(UnsupportedOperationException.class,
                () -> base.compileDropDatabaseIfExists(db, "a", false));
        assertThrows(UnsupportedOperationException.class, () -> base.compileDatabaseExists("a"));
        assertThrows(UnsupportedOperationException.class, () -> base.createDatabase(db, "a", null, null));
        assertThrows(UnsupportedOperationException.class, () -> base.dropDatabaseIfExists(db, "a", false));
        assertFalse(base.supportsCopyStrategy(db));
    }

    @Test
    void schemaRefusesCommandsTheGrammarDoesNotKnow() {
        Schema schema = Schema.of(db, new SchemaGrammar() { });

        UnsupportedOperationException error =
                assertThrows(UnsupportedOperationException.class, () -> schema.createDatabase("a"));
        assertTrue(error.getMessage().startsWith("Creating a database is not supported by"));
        assertThrows(UnsupportedOperationException.class, () -> schema.dropDatabaseIfExists("a"));
    }

    @Test
    void nullOrBlankNamesAreRefused() {
        Schema schema = Schema.of(db, new DatabaseGrammar());

        assertThrows(IllegalArgumentException.class, () -> schema.createDatabase(null));
        assertThrows(IllegalArgumentException.class, () -> schema.createDatabase(" "));
        assertThrows(IllegalArgumentException.class, () -> schema.createDatabase("a", " "));
        assertThrows(IllegalArgumentException.class, () -> schema.createDatabase("", "t", CopyStrategy.WAL_LOG));
        assertThrows(IllegalArgumentException.class, () -> schema.dropDatabaseIfExists(null));
        assertThrows(IllegalArgumentException.class, () -> schema.dropDatabaseIfExists("", true));
    }

    @Test
    void pretendLogsTheCompiledStatementsAndRunsNothing() {
        List<String> statements = pretended(new DatabaseGrammar(), schema -> {
            schema.createDatabase("a");
            schema.createDatabase("b", "tpl");
            schema.createDatabase("c", "tpl", CopyStrategy.FILE_COPY);
            assertFalse(schema.dropDatabaseIfExists("present"));
            schema.dropDatabaseIfExists("d", true);
        });

        assertEquals(List.of(
                "make a from null by null",
                "make b from tpl by null",
                "make c from tpl by FILE_COPY",
                "select 'drop present force false'",
                "select 'drop d force true'"), statements);
    }

    @Test
    void dropReturnsWhetherTheDatabaseExisted() {
        Schema schema = Schema.of(db, new DatabaseGrammar());

        assertTrue(schema.dropDatabaseIfExists("present"));
        assertFalse(schema.dropDatabaseIfExists("missing", true));
    }

    @Test
    void createRunsTheCompiledStatementOnTheConnection() {
        SchemaGrammar grammar = new DatabaseGrammar() {
            @Override
            public String compileCreateDatabase(Connection connection, String name, String template,
                    CopyStrategy strategy) {
                return "create table " + name + " (x integer)";
            }
        };
        Schema schema = Schema.of(db, grammar);

        schema.createDatabase("made");

        assertTrue(tableExists("made"));
    }

    private boolean tableExists(String name) {
        return !db.select("select 1 from sqlite_master where name = ?", List.of(name)).isEmpty();
    }
}
