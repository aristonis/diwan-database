package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.schema.IndexType;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** Looking an index up by name, columns and type on SQLite. */
class SQLiteHasIndexTest {

    @TempDir
    Path folder;

    private Connection db;
    private Schema schema;

    @BeforeEach
    void createDatabase() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("indexes.db"));
        db = Connection.builder().name("sqlite").dataSource(dataSource).build();
        schema = SQLiteSchema.of(db);
        db.statement("create table t (id integer primary key, a integer, b integer, c integer)");
        db.statement("create index t_a_b_index on t (a, b)");
        db.statement("create unique index t_c_unique on t (c)");
    }

    @Test
    void hasIndexFindsANameInAnyCase() {
        assertTrue(schema.hasIndex("t", "t_a_b_index"));
        assertTrue(schema.hasIndex("t", "T_A_B_INDEX"));
        assertFalse(schema.hasIndex("t", "t_missing"));
    }

    @Test
    void hasIndexMatchesColumnsInOrder() {
        assertTrue(schema.hasIndex("t", Arrays.asList("a", "b")));
        assertTrue(schema.hasIndex("t", Arrays.asList("A", "B")));
        assertFalse(schema.hasIndex("t", Arrays.asList("b", "a")));
        assertFalse(schema.hasIndex("t", Collections.singletonList("a")));
    }

    @Test
    void uniqueMatchesAUniqueIndexOnly() {
        assertTrue(schema.hasIndex("t", "t_c_unique", IndexType.UNIQUE));
        assertTrue(schema.hasIndex("t", Collections.singletonList("c"), IndexType.UNIQUE));
        assertFalse(schema.hasIndex("t", "t_a_b_index", IndexType.UNIQUE));
        assertFalse(schema.hasIndex("t", Arrays.asList("a", "b"), IndexType.UNIQUE));
    }

    @Test
    void primaryMatchesThePrimaryKey() {
        assertTrue(schema.hasIndex("t", "primary", IndexType.PRIMARY));
        assertTrue(schema.hasIndex("t", Collections.singletonList("id"), IndexType.PRIMARY));
        assertFalse(schema.hasIndex("t", "t_c_unique", IndexType.PRIMARY));
    }

    @Test
    void primaryMatchesOnAWithoutRowidTable() {
        db.statement("create table w (k text primary key, v text) without rowid");

        assertTrue(schema.hasIndex("w", Collections.singletonList("k"), IndexType.PRIMARY));
        assertTrue(schema.hasIndex("w", "primary", IndexType.PRIMARY));
    }

    @Test
    void primaryMatchesACompositeKeyInOrder() {
        db.statement("create table p (a integer, b integer, primary key (a, b))");

        assertTrue(schema.hasIndex("p", Arrays.asList("a", "b"), IndexType.PRIMARY));
        assertFalse(schema.hasIndex("p", Arrays.asList("b", "a"), IndexType.PRIMARY));
        assertFalse(schema.hasIndex("p", Collections.singletonList("a"), IndexType.PRIMARY));
    }

    @Test
    void anUnknownTableHasNoIndex() {
        assertFalse(schema.hasIndex("missing", "primary"));
        assertFalse(schema.hasIndex("missing", Collections.singletonList("a")));
    }

    @Test
    void theIndexListingIsLowerCaseAndIncludesPrimary() {
        db.statement("create index \"Mixed_Case\" on t (b)");

        assertEquals(Arrays.asList("mixed_case", "primary", "t_a_b_index", "t_c_unique"),
                sorted(schema.getIndexListing("t")));
    }

    private static List<String> sorted(List<String> names) {
        List<String> copy = new ArrayList<>(names);
        copy.sort(null);
        return copy;
    }
}
