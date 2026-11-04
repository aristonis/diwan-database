package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.junit.jupiter.api.Test;

/** Id parsing and join table row building for pivot writes. No database. */
class PivotRecordsTest {

    /** A related model whose related key ({@code code}) is not its primary key. */
    static final class Tag extends Model {
        static Tag of(Object id, Object code) {
            Tag tag = new Tag();
            tag.setAttribute("id", id);
            tag.setAttribute("code", code);
            return tag;
        }
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void parseIdsRefusesNullEntry() {
        assertThrows(IllegalArgumentException.class,
                () -> PivotRecords.parseIds(Arrays.asList(1, null), "code"));
        assertThrows(NullPointerException.class, () -> PivotRecords.parseIds(null, "code"));
    }

    @Test
    void parseIdsUsesRelatedKeyNotPrimaryKey() {
        assertEquals(Collections.singletonList("php"), PivotRecords.parseIds(Tag.of(7, "php"), "code"));
    }

    @Test
    void parseIdsAcceptsMixedIdsAndModels() {
        List<Object> mixed = Arrays.asList(1, Tag.of(9, 2), "1", 3);
        assertEquals(Arrays.<Object>asList(1, 2, "1", 3), PivotRecords.parseIds(mixed, "code"));

        PivotRecords.IdSet set = PivotRecords.parse(mixed, "code");
        assertEquals(Arrays.<Object>asList(1, 2, 3), set.ids());
        assertTrue(set.contains(1L));
        assertTrue(set.contains("2"));
        assertEquals(3, set.size());
    }

    @Test
    void parseAcceptsArrayOfIds() {
        assertEquals(Arrays.<Object>asList(4, 5), PivotRecords.parse(new Object[] {4, 5}, "code").ids());
    }

    @Test
    void parseRefusesModelWithoutKey() {
        assertThrows(IllegalArgumentException.class, () -> PivotRecords.parse(Tag.of(1, null), "code"));
    }

    @Test
    void parseAcceptsMapOfIdToAttributes() {
        Map<Object, Object> ids = new LinkedHashMap<>();
        ids.put(5, map("weight", 2));
        ids.put(6, null);
        PivotRecords.IdSet set = PivotRecords.parse(ids, "code");

        assertEquals(Arrays.<Object>asList(5, 6), set.ids());
        assertEquals(map("weight", 2), set.attributesOf("5"));
        assertTrue(set.attributesOf(6).isEmpty());
        assertFalse(set.isEmpty());
    }

    @Test
    void withSharedAttributesLetsSharedWin() {
        Map<Object, Object> ids = new LinkedHashMap<>();
        ids.put(5, map("weight", 2, "status", "new"));
        PivotRecords.IdSet set = PivotRecords.withSharedAttributes(PivotRecords.parse(ids, "code"),
                map("weight", 9));

        assertEquals(map("weight", 9, "status", "new"), set.attributesOf(5));
    }

    @Test
    void recordPutsRelatedKeyFirstThenTailThenAttributes() {
        Map<String, Object> row = PivotRecords.record("role_id", 3, map("user_id", 1), map("status", "x"));

        assertEquals(Arrays.asList("role_id", "user_id", "status"), Arrays.asList(row.keySet().toArray()));
    }

    @Test
    void requireSameColumnsRefusesDifferentSets() {
        List<Map<String, Object>> rows = Arrays.asList(map("a", 1, "b", 2), map("a", 1));

        assertThrows(IllegalArgumentException.class, () -> PivotRecords.requireSameColumns(rows));
        PivotRecords.requireSameColumns(Arrays.asList(map("a", 1), map("a", 2)));
        PivotRecords.requireSameColumns(Collections.<Map<String, Object>>emptyList());
    }

    @Test
    void rowsPerStatementNeverBelowOne() {
        assertEquals(300, PivotRecords.rowsPerStatement(3, 900));
        assertEquals(1, PivotRecords.rowsPerStatement(1000, 900));
    }

    @Test
    void castKeyReturnsLongOrString() {
        assertEquals(7L, PivotRecords.castKey("7", true));
        assertEquals(7L, PivotRecords.castKey(7, true));
        assertEquals("07", PivotRecords.castKey("07", true));
        assertEquals("7", PivotRecords.castKey(7, false));
    }

    @Test
    void findKeyUsesRelatedKeyOfModel() {
        assertEquals("php", PivotRecords.findKey(Tag.of(1, "php"), "code"));
        assertEquals(4, PivotRecords.findKey(4, "code"));
        assertEquals(Arrays.<Object>asList("go", 5),
                PivotRecords.findKeys(Arrays.asList(Tag.of(2, "go"), 5), "code"));
    }
}
