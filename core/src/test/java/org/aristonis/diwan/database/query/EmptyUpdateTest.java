package org.aristonis.diwan.database.query;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EmptyUpdateTest {

    private final RecordingConnection db = new RecordingConnection();

    @Test
    void anUpdateWithNoValuesIsRefusedBeforeAnyStatementRuns() {
        Builder query = db.db().table("t").where("id", 1);

        assertThrows(IllegalArgumentException.class, () -> query.update(Map.of()));

        assertTrue(db.calls.isEmpty());
    }
}
