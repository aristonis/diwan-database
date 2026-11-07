package org.aristonis.diwan.database.query;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The base grammar refuses the key-level lock strengths it cannot write. */
class QueryGrammarLockTest {

    @Test
    void baseGrammarStillRefusesKeyLevelStrengths() {
        QueryFactory plain = new RecordingConnection().db();

        for (LockStrength strength : List.of(LockStrength.NO_KEY_UPDATE, LockStrength.KEY_SHARE)) {
            assertThrows(UnsupportedOperationException.class, () -> plain.table("users").lock(strength).toSql());
        }
    }
}
