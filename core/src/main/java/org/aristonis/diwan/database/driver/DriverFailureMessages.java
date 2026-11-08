package org.aristonis.diwan.database.driver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.aristonis.diwan.database.Internal;
import org.aristonis.diwan.database.exception.DiwanError;

/**
 * Builds the message of a driver failure from its SQL state, vendor code, kinds and result code name.
 *
 * <p>The message is the fixed text {@code Database failure (SQL state <state|unknown>, vendor code
 * <n>)}, followed when there is anything to add by {@code : } and the recognised kinds as lower-case
 * words in declaration order, then the result code name. It never holds values, SQL or database
 * text.
 *
 * @apiNote Internal: for drivers in this library. The wording may change between versions.
 */
@Internal
public final class DriverFailureMessages {

    private static final String UNKNOWN_STATE = "unknown";
    private static final int PRIMARY_CODE_MASK = 0xff;

    private static final int SQLITE_BUSY = 5;
    private static final int SQLITE_LOCKED = 6;
    private static final int SQLITE_CONSTRAINT = 19;
    private static final int SQLITE_CONSTRAINT_CHECK = 275;
    private static final int SQLITE_CONSTRAINT_NOTNULL = 1299;
    private static final int SQLITE_CONSTRAINT_FOREIGNKEY = 787;
    private static final int SQLITE_CONSTRAINT_PRIMARYKEY = 1555;
    private static final int SQLITE_CONSTRAINT_UNIQUE = 2067;

    private DriverFailureMessages() {
    }

    /**
     * Builds the message of a driver failure.
     *
     * @param kinds the kinds the driver recognised, may be {@code null} or empty
     * @param sqlState the SQL state the database reported, or {@code null} when it gave none
     * @param vendorCode the vendor-specific error code, {@code 0} when there is none
     * @param codeName the database's own name for the result code, such as {@code SQLITE_BUSY}, or
     *     {@code null} when there is none
     * @return the message, never {@code null}; it never holds values, SQL or database text
     */
    public static String message(Set<DriverFailureKind> kinds, String sqlState, int vendorCode, String codeName) {
        String base = "Database failure (SQL state " + (sqlState == null ? UNKNOWN_STATE : sqlState)
                + ", vendor code " + vendorCode + ")";
        List<String> details = new ArrayList<>();
        for (DriverFailureKind kind : DriverFailureKind.values()) {
            if (kinds != null && kinds.contains(kind)) {
                details.add(words(kind));
            }
        }
        if (codeName != null) {
            details.add(codeName);
        }
        return details.isEmpty() ? base : base + ": " + String.join(", ", details);
    }

    /**
     * Builds the message of a driver failure with the Diwan error suffix.
     *
     * @param kinds the kinds the driver recognised, may be {@code null} or empty
     * @param sqlState the SQL state the database reported, or {@code null} when it gave none
     * @param vendorCode the vendor-specific error code, {@code 0} when there is none
     * @param codeName the database's own name for the result code, or {@code null} when there is none
     * @param error the Diwan error whose suffix is appended, never {@code null}
     * @return the text of {@link #message(Set, String, int, String)} followed by {@link #suffix(DiwanError)}
     */
    public static String message(Set<DriverFailureKind> kinds, String sqlState, int vendorCode, String codeName,
            DiwanError error) {
        return message(kinds, sqlState, vendorCode, codeName) + suffix(error);
    }

    /**
     * Builds the suffix that ends a failure message.
     *
     * <p>The form is {@code  [<code> <kind words>[: <key>][ (<columns>)]]}, for example
     * {@code  [DWN-CST-0001 unique violation: users_email_unique (email)]}, and {@code  [DWN-UNK-0000]}
     * alone for a failure Diwan does not recognise. It holds schema names, never values.
     *
     * @param error the Diwan error, never {@code null}
     * @return the suffix with a leading space, never {@code null}
     */
    public static String suffix(DiwanError error) {
        StringBuilder text = new StringBuilder(" [").append(error.code());
        Optional<DriverFailureKind> kind = DiwanErrorCodes.kindOf(error.code());
        if (kind.isPresent()) {
            text.append(' ').append(words(kind.get()));
            if (error.key().isPresent()) {
                text.append(": ").append(error.key().get());
            }
            if (!error.columns().isEmpty()) {
                text.append(" (").append(String.join(", ", error.columns())).append(')');
            }
        }
        return text.append(']').toString();
    }

    /**
     * Returns the name of a SQLite result code that Diwan classifies.
     *
     * <p>The exact code is tried first, so an extended code such as {@code 2067} gets its own name.
     * When it has none, the primary code ({@code code & 0xff}) is tried, which covers drivers that
     * report only primary codes.
     *
     * @param code the SQLite result code, primary or extended
     * @return the name, such as {@code SQLITE_BUSY}, or an empty optional when Diwan has none for it
     */
    public static Optional<String> sqliteCodeName(int code) {
        String name = exactSqliteName(code);
        if (name == null) {
            name = exactSqliteName(code & PRIMARY_CODE_MASK);
        }
        return Optional.ofNullable(name);
    }

    private static String words(DriverFailureKind kind) {
        return kind.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String exactSqliteName(int code) {
        switch (code) {
            case SQLITE_BUSY:
                return "SQLITE_BUSY";
            case SQLITE_LOCKED:
                return "SQLITE_LOCKED";
            case SQLITE_CONSTRAINT:
                return "SQLITE_CONSTRAINT";
            case SQLITE_CONSTRAINT_FOREIGNKEY:
                return "SQLITE_CONSTRAINT_FOREIGNKEY";
            case SQLITE_CONSTRAINT_PRIMARYKEY:
                return "SQLITE_CONSTRAINT_PRIMARYKEY";
            case SQLITE_CONSTRAINT_CHECK:
                return "SQLITE_CONSTRAINT_CHECK";
            case SQLITE_CONSTRAINT_NOTNULL:
                return "SQLITE_CONSTRAINT_NOTNULL";
            case SQLITE_CONSTRAINT_UNIQUE:
                return "SQLITE_CONSTRAINT_UNIQUE";
            default:
                return null;
        }
    }
}
