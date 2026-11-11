package org.aristonis.diwan.database.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndexInfoTest {

    private static final List<String> COLUMNS = Arrays.asList("a", "b");

    @Test
    void oldConstructorDefaultsToAscNoCollationNoWhere() {
        IndexInfo info = new IndexInfo("i", COLUMNS, true, false);

        assertEquals(Arrays.asList("asc", "asc"), info.orders());
        assertEquals(Arrays.asList("", ""), info.collations());
        assertTrue(!info.where().isPresent());
    }

    @Test
    void newConstructorKeepsItsParts() {
        IndexInfo info = new IndexInfo("i", COLUMNS, false, false, Arrays.asList("desc", "asc"),
                Arrays.asList("", "nocase"), "\"a\" is not null");

        assertEquals(Arrays.asList("desc", "asc"), info.orders());
        assertEquals(Arrays.asList("", "nocase"), info.collations());
        assertEquals("\"a\" is not null", info.where().get());
    }

    @Test
    void equalsAndHashCodeIncludeTheNewFields() {
        IndexInfo base = new IndexInfo("i", COLUMNS, false, false);

        assertEquals(base, new IndexInfo("i", COLUMNS, false, false, Arrays.asList("asc", "asc"),
                Arrays.asList("", ""), null));
        assertNotEquals(base, new IndexInfo("i", COLUMNS, false, false, Arrays.asList("desc", "asc"),
                Arrays.asList("", ""), null));
        assertNotEquals(base, new IndexInfo("i", COLUMNS, false, false, Arrays.asList("asc", "asc"),
                Arrays.asList("", "nocase"), null));
        assertNotEquals(base, new IndexInfo("i", COLUMNS, false, false, Arrays.asList("asc", "asc"),
                Arrays.asList("", ""), "x"));
        assertEquals(base.hashCode(), new IndexInfo("i", COLUMNS, false, false).hashCode());
    }
}
