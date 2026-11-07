package org.aristonis.diwan.database.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DriverFailureMessagesTest {

    private static final String OLD_TEXT = "Database failure (SQL state unknown, vendor code 5)";

    @Test
    void unknownKindKeepsTheOldText() {
        assertEquals(OLD_TEXT, DriverFailureMessages.message(null, null, 5, null));
        assertEquals(OLD_TEXT, DriverFailureMessages.message(Collections.<DriverFailureKind>emptySet(), null, 5, null));
    }

    @Test
    void kindsAreListedInAFixedOrder() {
        Set<DriverFailureKind> reversed = new LinkedHashSet<>(
                Arrays.asList(DriverFailureKind.COMMIT_OUTCOME_UNKNOWN, DriverFailureKind.UNIQUE_VIOLATION,
                        DriverFailureKind.CONNECTION_LOST));

        assertEquals("Database failure (SQL state 08006, vendor code 0): unique violation, connection lost, "
                + "commit outcome unknown", DriverFailureMessages.message(reversed, "08006", 0, null));
    }

    @Test
    void codeNameOnlyAppends() {
        assertEquals(OLD_TEXT + ": SQLITE_BUSY", DriverFailureMessages.message(null, null, 5, "SQLITE_BUSY"));
        assertEquals(OLD_TEXT + ": concurrency, SQLITE_BUSY", DriverFailureMessages.message(
                EnumSet.of(DriverFailureKind.CONCURRENCY), null, 5, "SQLITE_BUSY"));
    }

    @Test
    void extendedCodeFallsBackToPrimaryName() {
        assertEquals(Optional.of("SQLITE_BUSY"), DriverFailureMessages.sqliteCodeName(517));
        assertEquals(Optional.of("SQLITE_CONSTRAINT_UNIQUE"), DriverFailureMessages.sqliteCodeName(2067));
        assertFalse(DriverFailureMessages.sqliteCodeName(999999).isPresent());
        assertTrue(DriverFailureMessages.sqliteCodeName(19).isPresent());
    }
}
