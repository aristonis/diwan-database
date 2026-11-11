package org.aristonis.diwan.database.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** Checks the listings, hasColumns and hasIndex that Schema builds on the grammar's column and index reads. */
class SchemaInspectionTest {

    /** Returns fixed columns and indexes for the table app_users, and nothing for other tables. */
    static class StubGrammar extends SchemaTest.TestGrammar {
        @Override
        public List<ColumnInfo> columns(Connection connection, String table) {
            if (!table.equals("app_users")) {
                return Collections.emptyList();
            }
            return Arrays.asList(column("id"), column("Email"), column("name"));
        }

        @Override
        public List<IndexInfo> indexes(Connection connection, String table) {
            if (!table.equals("app_users")) {
                return Collections.emptyList();
            }
            return Arrays.asList(
                    new IndexInfo("PRIMARY", Arrays.asList("id"), true, true),
                    new IndexInfo("Users_Email_Unique", Arrays.asList("Email"), true, false),
                    new IndexInfo("users_name_email_index", Arrays.asList("name", "email"), false, false));
        }

        private static ColumnInfo column(String name) {
            return new ColumnInfo(name, "text", "text", null, true, null, false, null, null);
        }
    }

    @TempDir
    Path folder;

    private Schema schema;

    @BeforeEach
    void createSchema() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("test.db"));
        Connection db = Connection.builder().name("sqlite").tablePrefix("app_").dataSource(dataSource).build();
        schema = Schema.of(db, new StubGrammar());
    }

    @Test
    void getColumnsReturnsTheGrammarColumnsOfThePrefixedTable() {
        assertEquals(3, schema.getColumns("users").size());
        assertTrue(schema.getColumns("missing").isEmpty());
    }

    @Test
    void getColumnListingKeepsColumnOrder() {
        assertEquals(Arrays.asList("id", "Email", "name"), schema.getColumnListing("users"));
    }

    @Test
    void getIndexListingLowercasesNames() {
        assertEquals(Arrays.asList("primary", "users_email_unique", "users_name_email_index"),
                schema.getIndexListing("users"));
    }

    @Test
    void hasColumnsIsTrueWhenEveryColumnExistsIgnoringCase() {
        assertTrue(schema.hasColumns("users", Arrays.asList("ID", "email")));
    }

    @Test
    void hasColumnsIsFalseWhenOneIsMissing() {
        assertFalse(schema.hasColumns("users", Arrays.asList("id", "age")));
    }

    @Test
    void hasColumnsWithEmptyListIsTrue() {
        assertTrue(schema.hasColumns("users", Collections.<String>emptyList()));
    }

    @Test
    void hasColumnsOnUnknownTableIsFalse() {
        assertFalse(schema.hasColumns("missing", Arrays.asList("id")));
    }

    @Test
    void hasIndexByNameIgnoresCase() {
        assertTrue(schema.hasIndex("users", "USERS_EMAIL_UNIQUE"));
        assertFalse(schema.hasIndex("users", "users_other_index"));
    }

    @Test
    void hasIndexByColumnsNeedsSameOrderAndCount() {
        assertTrue(schema.hasIndex("users", Arrays.asList("NAME", "Email")));
        assertFalse(schema.hasIndex("users", Arrays.asList("email", "name")));
        assertFalse(schema.hasIndex("users", Arrays.asList("name")));
        assertFalse(schema.hasIndex("users", Arrays.asList("name", "email", "id")));
    }

    @Test
    void hasIndexByNameChecksPrimaryAndUniqueTypes() {
        assertTrue(schema.hasIndex("users", "primary", IndexType.PRIMARY));
        assertTrue(schema.hasIndex("users", "primary", IndexType.UNIQUE));
        assertTrue(schema.hasIndex("users", "users_email_unique", IndexType.UNIQUE));
        assertFalse(schema.hasIndex("users", "users_email_unique", IndexType.PRIMARY));
        assertFalse(schema.hasIndex("users", "users_name_email_index", IndexType.UNIQUE));
    }

    @Test
    void hasIndexByColumnsChecksPrimaryAndUniqueTypes() {
        assertTrue(schema.hasIndex("users", Arrays.asList("id"), IndexType.PRIMARY));
        assertTrue(schema.hasIndex("users", Arrays.asList("id"), IndexType.UNIQUE));
        assertTrue(schema.hasIndex("users", Arrays.asList("email"), IndexType.UNIQUE));
        assertFalse(schema.hasIndex("users", Arrays.asList("email"), IndexType.PRIMARY));
        assertFalse(schema.hasIndex("users", Arrays.asList("name", "email"), IndexType.UNIQUE));
    }

    @Test
    void hasIndexRejectsAnEmptyIndexOrColumnName() {
        assertThrows(IllegalArgumentException.class, () -> schema.hasIndex("users", ""));
        assertThrows(IllegalArgumentException.class, () -> schema.hasIndex("users", "", IndexType.UNIQUE));
        assertThrows(IllegalArgumentException.class, () -> schema.hasIndex("users", Arrays.asList("")));
        assertThrows(IllegalArgumentException.class,
                () -> schema.hasIndex("users", Arrays.asList(""), IndexType.UNIQUE));
    }

    @Test
    void hasIndexOnUnknownTableIsFalse() {
        assertFalse(schema.hasIndex("missing", "primary"));
        assertFalse(schema.hasIndex("missing", Arrays.asList("id")));
        assertFalse(schema.hasIndex("missing", "primary", IndexType.PRIMARY));
        assertFalse(schema.hasIndex("missing", Arrays.asList("id"), IndexType.PRIMARY));
    }

    @Test
    void nullsAreRefused() {
        assertThrows(NullPointerException.class, () -> schema.hasIndex("users", (String) null));
        assertThrows(NullPointerException.class, () -> schema.hasIndex("users", "primary", null));
        assertThrows(NullPointerException.class, () -> schema.hasColumns("users", null));
    }

    @Test
    void baseGrammarRefusesColumnListing() {
        Schema plain = Schema.of(connectionOf(), new SchemaTest.TestGrammar());

        assertThrows(UnsupportedOperationException.class, () -> plain.getColumns("users"));
    }

    private Connection connectionOf() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("other.db"));
        return Connection.builder().name("sqlite").dataSource(dataSource).build();
    }
}
