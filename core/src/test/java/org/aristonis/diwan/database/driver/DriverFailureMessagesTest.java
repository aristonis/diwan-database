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
import org.aristonis.diwan.database.exception.DiwanError;
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

    @Test
    void suffixNamesTheCodeKindKeyAndColumns() {
        DiwanError error = DiwanError.of(EnumSet.of(DriverFailureKind.UNIQUE_VIOLATION),
                "users_email_unique", Arrays.asList("email"));

        assertEquals(" [DWN-CST-0001 unique violation: users_email_unique (email)]",
                DriverFailureMessages.suffix(error));
    }

    @Test
    void suffixListsSeveralColumnsAndOmitsWhatIsMissing() {
        assertEquals(" [DWN-CST-0004 not null violation (a, b)]", DriverFailureMessages.suffix(DiwanError.of(
                EnumSet.of(DriverFailureKind.NOT_NULL_VIOLATION), null, Arrays.asList("a", "b"))));
        assertEquals(" [DWN-CST-0003 check violation: positive]", DriverFailureMessages.suffix(DiwanError.of(
                EnumSet.of(DriverFailureKind.CHECK_VIOLATION), "positive", Collections.<String>emptyList())));
    }

    @Test
    void suffixOfAnUnknownFailureIsTheCodeAlone() {
        assertEquals(" [DWN-UNK-0000]", DriverFailureMessages.suffix(
                DiwanError.of(null, null, Collections.<String>emptyList())));
    }

    @Test
    void messageWithAnErrorAppendsTheSuffixToTheOldText() {
        DiwanError error = DiwanError.of(EnumSet.of(DriverFailureKind.CONCURRENCY), null,
                Collections.<String>emptyList());

        assertEquals(OLD_TEXT + ": concurrency [DWN-TXN-0001 concurrency]", DriverFailureMessages.message(
                EnumSet.of(DriverFailureKind.CONCURRENCY), null, 5, null, error));
    }

    @Test
    void checkAndNotNullCodesHaveNames() {
        assertEquals(Optional.of("SQLITE_CONSTRAINT_CHECK"), DriverFailureMessages.sqliteCodeName(275));
        assertEquals(Optional.of("SQLITE_CONSTRAINT_NOTNULL"), DriverFailureMessages.sqliteCodeName(1299));
    }
}
