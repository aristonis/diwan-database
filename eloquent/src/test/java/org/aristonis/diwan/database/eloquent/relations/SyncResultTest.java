package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The value a sync or toggle returns. */
class SyncResultTest {

    private static final List<Object> NONE = Collections.emptyList();

    @Test
    void listsAreUnmodifiable() {
        SyncResult result = new SyncResult(Arrays.<Object>asList(1L), Arrays.<Object>asList(2L), NONE);

        assertThrows(UnsupportedOperationException.class, () -> result.getAttached().add(3L));
        assertThrows(UnsupportedOperationException.class, () -> result.getDetached().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.getUpdated().add(4L));
    }

    @Test
    void listsDoNotFollowTheCallersList() {
        List<Object> attached = new ArrayList<>(Arrays.<Object>asList(1L));
        SyncResult result = new SyncResult(attached, NONE, NONE);
        attached.add(2L);

        assertEquals(Arrays.<Object>asList(1L), result.getAttached());
    }

    @Test
    void keysAreCastToRelatedKeyType() {
        SyncResult result = new SyncResult(
                Arrays.asList(PivotRecords.castKey("3", true)),
                Arrays.asList(PivotRecords.castKey("en", false)), NONE);

        assertEquals(Arrays.<Object>asList(3L), result.getAttached());
        assertEquals(Arrays.<Object>asList("en"), result.getDetached());
    }

    @Test
    void isEmptyTrueOnlyWhenAllListsEmpty() {
        assertTrue(new SyncResult(NONE, NONE, NONE).isEmpty());
        assertFalse(new SyncResult(NONE, NONE, Arrays.<Object>asList(1L)).isEmpty());
    }

    @Test
    void equalsAndHashCodeCompareLists() {
        SyncResult a = new SyncResult(Arrays.<Object>asList(1L), NONE, NONE);
        SyncResult b = new SyncResult(Arrays.<Object>asList(1L), NONE, NONE);
        SyncResult c = new SyncResult(NONE, Arrays.<Object>asList(1L), NONE);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertEquals("SyncResult{attached=[1], detached=[], updated=[]}", a.toString());
    }
}
