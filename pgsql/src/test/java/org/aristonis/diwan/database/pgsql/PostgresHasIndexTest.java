package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.schema.IndexType;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Index presence checks on PostgreSQL. */
class PostgresHasIndexTest {

    private Connection db;
    private Schema schema;

    @BeforeEach
    void createDatabase() {
        db = PostgresServer.newDatabase();
        schema = PostgresSchema.of(db);
        db.statement("create table t (id int primary key, a int, b int, c int)");
        db.statement("create index t_a_b_index on t (a, b)");
        db.statement("create unique index t_c_unique on t (c)");
        db.statement("create table pairs (x int, y int, primary key (x, y))");
    }

    @Test
    void hasIndexByNameIgnoresLetterCase() {
        assertTrue(schema.hasIndex("t", "t_a_b_index"));
        assertTrue(schema.hasIndex("t", "T_A_B_INDEX"));
        assertFalse(schema.hasIndex("t", "missing"));
    }

    @Test
    void hasIndexByColumnsKeepsColumnOrder() {
        assertTrue(schema.hasIndex("t", Arrays.asList("a", "b")));
        assertFalse(schema.hasIndex("t", Arrays.asList("b", "a")));
    }

    @Test
    void hasIndexByTypeSeparatesPrimaryFromUnique() {
        assertTrue(schema.hasIndex("t", Arrays.asList("c"), IndexType.UNIQUE));
        assertFalse(schema.hasIndex("t", Arrays.asList("c"), IndexType.PRIMARY));
        assertTrue(schema.hasIndex("t", Arrays.asList("id"), IndexType.PRIMARY));
        assertFalse(schema.hasIndex("t", Arrays.asList("a", "b"), IndexType.UNIQUE));
        assertTrue(schema.hasIndex("t", "t_c_unique", IndexType.UNIQUE));
        assertTrue(schema.hasIndex("t", "t_pkey", IndexType.PRIMARY));
    }

    @Test
    void compositePrimaryKeyIsOneIndexInKeyOrder() {
        assertTrue(schema.hasIndex("pairs", Arrays.asList("x", "y"), IndexType.PRIMARY));
        assertFalse(schema.hasIndex("pairs", Arrays.asList("y", "x"), IndexType.PRIMARY));
    }

    @Test
    void unknownTableHasNoIndex() {
        assertFalse(schema.hasIndex("nope", "anything"));
        assertFalse(schema.hasIndex("nope", Arrays.asList("id")));
    }

    @Test
    void indexListingIsLowerCase() {
        db.statement("create index \"Mixed_Idx\" on t (a)");

        List<String> names = new ArrayList<>(schema.getIndexListing("t"));
        Collections.sort(names);
        assertEquals(Arrays.asList("mixed_idx", "t_a_b_index", "t_c_unique", "t_pkey"), names);
    }
}
