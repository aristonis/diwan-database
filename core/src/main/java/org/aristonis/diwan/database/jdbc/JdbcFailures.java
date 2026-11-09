package org.aristonis.diwan.database.jdbc;

import java.sql.SQLException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Set;
import org.aristonis.diwan.database.driver.ConstraintDetails;
import org.aristonis.diwan.database.driver.DatabaseFamily;
import org.aristonis.diwan.database.driver.DriverFailureKind;
import org.aristonis.diwan.database.driver.DriverFailureMessages;
import org.aristonis.diwan.database.exception.DiwanError;
import org.aristonis.diwan.database.exception.DriverException;

/** Translates JDBC failures into {@link DriverException}s without exposing database text. */
final class JdbcFailures {

    private static final String UNIQUE_STATE = "23505";
    private static final String SERIALIZATION_STATE = "40001";
    private static final String DEADLOCK_STATE = "40P01";
    private static final String CONNECTION_STATE_CLASS = "08";
    private static final String FOREIGN_KEY_STATE = "23503";
    // PostgreSQL raises restrict_violation, not 23503, for a key declared ON DELETE/UPDATE RESTRICT.
    private static final String RESTRICT_STATE = "23001";
    private static final String CHECK_STATE = "23514";
    private static final String NOT_NULL_STATE = "23502";
    private static final String CANCELLED_STATE = "57014";
    private static final String LOCK_NOT_AVAILABLE_STATE = "55P03";
    private static final String[] SERVER_GONE_STATES = {"57P01", "57P02", "57P03"};
    private static final int SQLITE_CONSTRAINT_FOREIGNKEY = 787;
    private static final String[] SQLITE_FOREIGN_KEY_MARKERS = {
        "SQLITE_CONSTRAINT_FOREIGNKEY", "FOREIGN KEY constraint failed"
    };

    private static final String[] SQLITE_CHECK_MARKERS = {
        "SQLITE_CONSTRAINT_CHECK", "CHECK constraint failed"
    };
    private static final String[] SQLITE_NOT_NULL_MARKERS = {
        "SQLITE_CONSTRAINT_NOTNULL", "NOT NULL constraint failed"
    };

    private static final int MYSQL_DUPLICATE = 1062;
    private static final int SQLITE_CONSTRAINT = 19;
    private static final int SQLITE_CONSTRAINT_UNIQUE = 2067;
    private static final int SQLITE_CONSTRAINT_PRIMARYKEY = 1555;
    private static final int SQLSERVER_UNIQUE_CONSTRAINT = 2627;
    private static final int SQLSERVER_UNIQUE_INDEX = 2601;
    private static final int ORACLE_UNIQUE = 1;

    private static final int MYSQL_DEADLOCK = 1213;
    private static final int SQLSERVER_DEADLOCK = 1205;
    private static final int SQLITE_BUSY = 5;
    private static final int SQLITE_LOCKED = 6;
    private static final int SQLITE_BUSY_RECOVERY = 261;
    private static final int SQLITE_BUSY_SNAPSHOT = 517;
    private static final int SQLITE_LOCKED_SHAREDCACHE = 262;

    // xerial sqlite-jdbc reports only the primary code (19) as the vendor code and names the
    // extended code in the message, e.g. "[SQLITE_CONSTRAINT_UNIQUE] ...". The message is
    // inspected here but never copied into the DriverException.
    private static final String[] SQLITE_UNIQUE_MARKERS = {
        "SQLITE_CONSTRAINT_UNIQUE", "SQLITE_CONSTRAINT_PRIMARYKEY", "UNIQUE constraint failed"
    };

    private static final int ORACLE_DEADLOCK = 60;
    private static final int ORACLE_SERIALIZATION = 8177;

    /** Bounds the walk over a very long cause chain. */
    private static final int MAX_CHAIN = 32;

    private JdbcFailures() {
    }

    /**
     * Translates a JDBC failure.
     *
     * @param failure the failure, never {@code null}; kept as the cause
     * @param productName the database product name, or {@code null} if unknown
     * @return the driver failure, never {@code null}
     */
    static DriverException translate(SQLException failure, String productName) {
        DatabaseFamily family = DatabaseFamily.of(productName);
        String state = null;
        int vendorCode = 0;
        String databaseMessage = null;
        boolean isConnectionFailure = false;
        SQLException first = null;
        Set<DriverFailureKind> kinds = EnumSet.noneOf(DriverFailureKind.class);
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = failure; t != null && visited.size() < MAX_CHAIN && visited.add(t); t = next(t)) {
            if (!(t instanceof SQLException)) {
                continue;
            }
            SQLException sql = (SQLException) t;
            if (first == null) {
                first = sql;
            }
            String s = sql.getSQLState();
            int code = sql.getErrorCode();
            if (state == null && s != null) {
                state = s;
            }
            if (vendorCode == 0 && code != 0) {
                vendorCode = code;
            }
            if (databaseMessage == null && sql.getMessage() != null) {
                databaseMessage = sql.getMessage();
            }
            if (s != null && (s.startsWith(CONNECTION_STATE_CLASS) || isServerGone(s))) {
                isConnectionFailure = true;
            }
            if (FOREIGN_KEY_STATE.equals(s) || RESTRICT_STATE.equals(s) || family == DatabaseFamily.SQLITE
                    && (code == SQLITE_CONSTRAINT_FOREIGNKEY || code == SQLITE_CONSTRAINT
                    && mentions(sql.getMessage(), SQLITE_FOREIGN_KEY_MARKERS))) {
                kinds.add(DriverFailureKind.FOREIGN_KEY_VIOLATION);
            }
            if (CANCELLED_STATE.equals(s)) {
                kinds.add(DriverFailureKind.CANCELLED);
            }
            if (CHECK_STATE.equals(s) || (family == DatabaseFamily.SQLITE
                    && mentions(sql.getMessage(), SQLITE_CHECK_MARKERS))) {
                kinds.add(DriverFailureKind.CHECK_VIOLATION);
            }
            if (NOT_NULL_STATE.equals(s) || (family == DatabaseFamily.SQLITE
                    && mentions(sql.getMessage(), SQLITE_NOT_NULL_MARKERS))) {
                kinds.add(DriverFailureKind.NOT_NULL_VIOLATION);
            }
            if (isUnique(family, s, code, sql.getMessage())) {
                kinds.add(DriverFailureKind.UNIQUE_VIOLATION);
            }
            if (isConcurrency(family, s, code)) {
                kinds.add(DriverFailureKind.CONCURRENCY);
            }
        }
        if (isConnectionFailure) {
            kinds.remove(DriverFailureKind.CONCURRENCY);
            kinds.add(DriverFailureKind.CONNECTION_LOST);
        }
        ConstraintDetails details = details(family, kinds, first);
        DiwanError error = DiwanError.of(kinds, details.key().orElse(null), details.table().orElse(null),
                details.columns());
        return new DriverException(failureMessage(family, kinds, state, vendorCode, error), state, vendorCode,
                databaseMessage, failure, kinds, error.key().orElse(null), error.table().orElse(null), error.columns());
    }

    /** Reads the constraint name and columns from the first SQL exception, for constraint failures only. */
    private static ConstraintDetails details(DatabaseFamily family, Set<DriverFailureKind> kinds, SQLException first) {
        boolean isConstraint = kinds.contains(DriverFailureKind.UNIQUE_VIOLATION)
                || kinds.contains(DriverFailureKind.FOREIGN_KEY_VIOLATION)
                || kinds.contains(DriverFailureKind.CHECK_VIOLATION)
                || kinds.contains(DriverFailureKind.NOT_NULL_VIOLATION);
        if (first == null || !isConstraint) {
            return ConstraintDetails.sqlite(null);
        }
        if (family == DatabaseFamily.POSTGRESQL) {
            return ConstraintDetails.postgres(first, kinds.contains(DriverFailureKind.UNIQUE_VIOLATION));
        }
        if (family == DatabaseFamily.SQLITE) {
            return ConstraintDetails.sqlite(first.getMessage());
        }
        return ConstraintDetails.sqlite(null);
    }

    /** Builds the failure message; the SQLite result code name is added for the SQLite family only. */
    private static String failureMessage(DatabaseFamily family, Set<DriverFailureKind> kinds, String state,
            int vendorCode, DiwanError error) {
        String codeName = family == DatabaseFamily.SQLITE
                ? DriverFailureMessages.sqliteCodeName(vendorCode).orElse(null)
                : null;
        return DriverFailureMessages.message(kinds, state, vendorCode, codeName, error);
    }

    private static Throwable next(Throwable t) {
        if (t instanceof SQLException) {
            SQLException nextException = ((SQLException) t).getNextException();
            if (nextException != null) {
                return nextException;
            }
        }
        return t.getCause();
    }

    private static boolean isUnique(DatabaseFamily family, String state, int code, String text) {
        if (UNIQUE_STATE.equals(state)) {
            return true;
        }
        switch (family) {
            case MYSQL:
                return code == MYSQL_DUPLICATE;
            case SQLITE:
                return code == SQLITE_CONSTRAINT_UNIQUE || code == SQLITE_CONSTRAINT_PRIMARYKEY
                        || (code == SQLITE_CONSTRAINT && mentionsSqliteUnique(text));
            case SQLSERVER:
                return code == SQLSERVER_UNIQUE_CONSTRAINT || code == SQLSERVER_UNIQUE_INDEX;
            case ORACLE:
                return code == ORACLE_UNIQUE;
            default:
                return false;
        }
    }

    private static boolean isServerGone(String state) {
        for (String gone : SERVER_GONE_STATES) {
            if (gone.equals(state)) {
                return true;
            }
        }
        return false;
    }

    private static boolean mentionsSqliteUnique(String text) {
        return mentions(text, SQLITE_UNIQUE_MARKERS);
    }

    private static boolean mentions(String text, String[] markers) {
        if (text == null) {
            return false;
        }
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConcurrency(DatabaseFamily family, String state, int code) {
        if (SERIALIZATION_STATE.equals(state) || DEADLOCK_STATE.equals(state)
                || LOCK_NOT_AVAILABLE_STATE.equals(state)) {
            return true;
        }
        switch (family) {
            case MYSQL:
                return code == MYSQL_DEADLOCK;
            case SQLSERVER:
                return code == SQLSERVER_DEADLOCK;
            case SQLITE:
                return code == SQLITE_BUSY || code == SQLITE_LOCKED || code == SQLITE_BUSY_RECOVERY
                        || code == SQLITE_LOCKED_SHAREDCACHE || code == SQLITE_BUSY_SNAPSHOT;
            case ORACLE:
                return code == ORACLE_DEADLOCK || code == ORACLE_SERIALIZATION;
            default:
                return false;
        }
    }
}
