package org.aristonis.diwan.database.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.LoggedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** Exercises the core with a small grammar, over SQLite for the tests that need a real database. */
class SchemaTest {

    /** Compiles a few commands and every type as its enum name, to show what the base class does. */
    static class TestGrammar extends SchemaGrammar {
        @Override
        protected String compileCreate(Blueprint blueprint, Command command, Connection connection) {
            List<String> columns = new ArrayList<>();
            for (ColumnDefinition column : blueprint.columns()) {
                columns.add(column(blueprint, column));
            }
            return "create table " + wrapTable(blueprint.table()) + " (" + String.join(", ", columns) + ")";
        }

        @Override
        protected String type(ColumnDefinition column) {
            return column.type() == ColumnType.JSON ? super.type(column) : column.type().name();
        }

        @Override
        protected String modifiers(Blueprint blueprint, ColumnDefinition column) {
            return column.defaultValue().isPresent() ? " default " + literal(column.defaultValue().get()) : "";
        }

        @Override
        public SchemaQuery compileTableExists(Connection connection, String table) {
            return new SchemaQuery("select count(*) from sqlite_master where type = 'table' and name = ?",
                    java.util.Collections.singletonList(table));
        }
    }

    @TempDir
    Path folder;

    private Connection db;

    @BeforeEach
    void createDatabase() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("test.db"));
        db = Connection.builder().name("sqlite").tablePrefix("app_").dataSource(dataSource).build();
    }

    private List<String> sql(java.util.function.Consumer<Schema> work) {
        List<String> statements = new ArrayList<>();
        for (LoggedQuery query : db.pretend(p -> work.accept(Schema.of(p, new TestGrammar())))) {
            statements.add(query.sql());
        }
        return statements;
    }

    @Test
    void quotesNamesAndEscapesDefaults() {
        List<String> statements = sql(schema -> schema.create("we\"ird", t -> {
            t.string("na\"me").defaultValue("it's");
            t.booleanColumn("on").defaultValue(true);
            t.booleanColumn("off").defaultValue(false);
            t.decimal("price").defaultValue(new BigDecimal("1E+3"));
            t.timestamp("at").useCurrent();
            t.string("email").unique();
        }));

        assertEquals(Arrays.asList(
                "create table \"app_we\"\"ird\" (\"na\"\"me\" STRING default 'it''s', \"on\" BOOLEAN default '1',"
                        + " \"off\" BOOLEAN default '0', \"price\" DECIMAL default '1000',"
                        + " \"at\" TIMESTAMP default CURRENT_TIMESTAMP, \"email\" STRING)",
                "create unique index \"app_we\"\"ird_email_unique\" on \"app_we\"\"ird\" (\"email\")"),
                statements);
    }

    @Test
    void baseGrammarDropsAndRefusesWhatItDoesNotKnow() {
        assertEquals(Arrays.asList("drop table \"app_users\""), sql(schema -> schema.drop("users")));
        assertEquals(Arrays.asList("drop table if exists \"app_users\""), sql(schema -> schema.dropIfExists("users")));

        Schema schema = Schema.of(db, new TestGrammar());
        UnsupportedOperationException rename =
                assertThrows(UnsupportedOperationException.class, () -> schema.rename("a", "b"));
        assertEquals("The command rename is not supported by TestGrammar", rename.getMessage());
        assertThrows(UnsupportedOperationException.class, () -> schema.table("a", t -> t.text("x")));
        assertThrows(UnsupportedOperationException.class, () -> schema.create("a", t -> t.json("x")));
        assertThrows(UnsupportedOperationException.class, () -> schema.hasColumn("a", "x"));
        assertThrows(UnsupportedOperationException.class, () -> new SchemaGrammar() { }.compileTableExists(db, "t"));
        assertThrows(UnsupportedOperationException.class,
                () -> Schema.of(db, new SchemaGrammar() { }).create("a", t -> t.text("x")));
        assertEquals(63, new SchemaGrammar() { }.maxIdentifierLength());
    }

    @Test
    void unknownCommandsAreRefused() {
        Blueprint blueprint = new Blueprint("t", "", false);
        blueprint.addCommand("spatialIndex", java.util.Collections.<String, Object>emptyMap());

        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> new TestGrammar().compile(blueprint, db));
        assertTrue(error.getMessage().contains("spatialIndex"));
    }

    @Test
    void checksArgumentsBeforeAnySqlRuns() {
        Schema schema = Schema.of(db, new TestGrammar());

        assertThrows(NullPointerException.class, () -> Schema.of(null, new TestGrammar()));
        assertThrows(NullPointerException.class, () -> Schema.of(db, null));
        assertThrows(NullPointerException.class, () -> schema.create("t", null));
        assertThrows(NullPointerException.class, () -> schema.table("t", null));
        assertThrows(IllegalArgumentException.class, () -> schema.create("t", t -> { }));
        assertThrows(IllegalArgumentException.class, () -> schema.drop(""));
        assertThrows(IllegalArgumentException.class, () -> schema.rename("a", "b\nc"));
        assertThrows(IllegalArgumentException.class, () -> schema.hasColumn("t", ""));
        assertSame(db, schema.connection());
        assertTrue(schema.grammar() instanceof TestGrammar);
    }

    @Test
    void severalStatementsRunInOneTransactionOnTransactionalDdl() {
        Schema schema = Schema.of(db, new TestGrammar());

        assertThrows(org.aristonis.diwan.database.QueryException.class, () -> schema.create("users", t -> {
            t.string("email").unique("app_users");
        }));

        assertFalse(schema.hasTable("users"), "the create rolled back with the failed index");
    }

    @Test
    void hasTableRefusesAResultThatIsNotACount() {
        SchemaGrammar odd = new TestGrammar() {
            @Override
            public SchemaQuery compileTableExists(Connection connection, String table) {
                return new SchemaQuery("select 'many'", java.util.Collections.emptyList());
            }
        };
        SchemaGrammar text = new TestGrammar() {
            @Override
            public SchemaQuery compileTableExists(Connection connection, String table) {
                return new SchemaQuery("select ' 1 '", java.util.Collections.emptyList());
            }
        };

        assertThrows(IllegalStateException.class, () -> Schema.of(db, odd).hasTable("t"));
        assertTrue(Schema.of(db, text).hasTable("t"));
    }

    @Test
    void defaultNullIsWrittenAsSqlNull() {
        assertEquals(Arrays.asList("create table \"app_t\" (\"a\" STRING default null)"),
                sql(schema -> schema.create("t", t -> t.string("a").defaultValue(null))));
    }

    @Test
    void hasTableReadsTheCatalog() {
        Schema schema = Schema.of(db, new TestGrammar());

        schema.create("users", t -> t.string("email"));

        assertTrue(schema.hasTable("users"));
        assertFalse(schema.hasTable("missing"));
    }
}
