package org.aristonis.diwan.database.jdbc;

import java.sql.SQLException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Set;
import org.aristonis.diwan.database.driver.DatabaseFamily;
import org.aristonis.diwan.database.exception.DriverException;
import org.aristonis.diwan.database.driver.DriverFailureKind;

/** Translates JDBC failures into {@link DriverException}s without exposing database text. */
final class JdbcFailures {

    private static final String UNIQUE_STATE = "23505";
    private static final String SERIALIZATION_STATE = "40001";
    private static final String DEADLOCK_STATE = "40P01";
    private static final String CONNECTION_STATE_CLASS = "08";
    private static final String FOREIGN_KEY_STATE = "23503";
    private static final String CANCELLED_STATE = "57014";
    private static final String LOCK_NOT_AVAILABLE_STATE = "55P03";
    private static final String[] SERVER_GONE_STATES = {"57P01", "57P02", "57P03"};
    private static final int SQLITE_CONSTRAINT_FOREIGNKEY = 787;
    private static final String[] SQLITE_FOREIGN_KEY_MARKERS = {
        "SQLITE_CONSTRAINT_FOREIGNKEY", "FOREIGN KEY constraint failed"
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
        Set<DriverFailureKind> kinds = EnumSet.noneOf(DriverFailureKind.class);
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = failure; t != null && visited.size() < MAX_CHAIN && visited.add(t); t = next(t)) {
            if (!(t instanceof SQLException)) {
                continue;
            }
            SQLException sql = (SQLException) t;
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
            if (FOREIGN_KEY_STATE.equals(s) || family == DatabaseFamily.SQLITE
                    && (code == SQLITE_CONSTRAINT_FOREIGNKEY || code == SQLITE_CONSTRAINT
                    && mentions(sql.getMessage(), SQLITE_FOREIGN_KEY_MARKERS))) {
                kinds.add(DriverFailureKind.FOREIGN_KEY_VIOLATION);
            }
            if (CANCELLED_STATE.equals(s)) {
                kinds.add(DriverFailureKind.CANCELLED);
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
        return new DriverException(
                message(state, vendorCode), state, vendorCode, databaseMessage, failure, kinds);
    }

    /**
     * Builds the fixed failure message from the SQL state and vendor code.
     *
     * @param state the SQL state, or {@code null}
     * @param vendorCode the vendor code
     * @return the message, never containing database text
     */
    static String message(String state, int vendorCode) {
        return "Database failure (SQL state " + (state == null ? "unknown" : state) + ", vendor code "
                + vendorCode + ")";
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
