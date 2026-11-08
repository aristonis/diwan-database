package org.aristonis.diwan.database.driver;

import java.util.Optional;
import java.util.Set;
import org.aristonis.diwan.database.Internal;

/**
 * The table that maps a failure kind to its stable Diwan error code.
 *
 * <p>The rows are in precedence order: when a failure has several kinds, the code of the first row
 * whose kind is present wins. Codes are public API and are never renumbered or reused.
 *
 * @apiNote Internal: the table is read through {@code DiwanError}; only the codes are public.
 */
@Internal
public final class DiwanErrorCodes {

    /** The code of a failure Diwan does not recognise. */
    public static final String UNKNOWN = "DWN-UNK-0000";

    /**
     * The code reserved for a lock wait failure. No kind maps to it yet, so no failure reports it;
     * the number is taken so that it is never reused.
     */
    public static final String LOCK_WAIT_RESERVED = "DWN-TXN-0002";

    private static final DriverFailureKind[] KINDS = {
        DriverFailureKind.UNIQUE_VIOLATION,
        DriverFailureKind.FOREIGN_KEY_VIOLATION,
        DriverFailureKind.CHECK_VIOLATION,
        DriverFailureKind.NOT_NULL_VIOLATION,
        DriverFailureKind.CONCURRENCY,
        DriverFailureKind.COMMIT_OUTCOME_UNKNOWN,
        DriverFailureKind.CONNECTION_LOST,
        DriverFailureKind.CANCELLED
    };

    private static final String[] CODES = {
        "DWN-CST-0001",
        "DWN-CST-0002",
        "DWN-CST-0003",
        "DWN-CST-0004",
        "DWN-TXN-0001",
        "DWN-TXN-0003",
        "DWN-CNX-0001",
        "DWN-QRY-0001"
    };

    private DiwanErrorCodes() {
    }

    /**
     * Picks the code for a set of kinds by precedence.
     *
     * @param kinds the kinds of the failure, may be {@code null} or empty
     * @return the code of the first row in the table whose kind is present, else {@link #UNKNOWN}
     */
    public static String codeOf(Set<DriverFailureKind> kinds) {
        if (kinds == null) {
            return UNKNOWN;
        }
        for (int i = 0; i < KINDS.length; i++) {
            if (kinds.contains(KINDS[i])) {
                return CODES[i];
            }
        }
        return UNKNOWN;
    }

    /**
     * Finds the kind a code stands for.
     *
     * @param code a Diwan error code
     * @return the kind, or an empty optional for {@link #UNKNOWN}, the reserved code or any other text
     */
    public static Optional<DriverFailureKind> kindOf(String code) {
        for (int i = 0; i < CODES.length; i++) {
            if (CODES[i].equals(code)) {
                return Optional.of(KINDS[i]);
            }
        }
        return Optional.empty();
    }
}
