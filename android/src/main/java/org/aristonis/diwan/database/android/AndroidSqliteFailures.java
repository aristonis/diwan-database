package org.aristonis.diwan.database.android;

import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabaseLockedException;
import android.database.sqlite.SQLiteException;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.aristonis.diwan.database.exception.DriverException;
import org.aristonis.diwan.database.driver.DriverFailureKind;
import org.aristonis.diwan.database.driver.DriverFailureMessages;

/** Turns Android's SQLite exceptions into driver failures, the way the JDBC driver does for SQLite. */
final class AndroidSqliteFailures {

    private static final int SQLITE_CONSTRAINT_UNIQUE = 2067;
    private static final int SQLITE_CONSTRAINT_PRIMARYKEY = 1555;
    private static final int SQLITE_BUSY = 5;
    private static final int SQLITE_LOCKED = 6;
    private static final int PRIMARY_CODE_MASK = 0xff;

    // Android appends "(code 2067 SQLITE_CONSTRAINT_UNIQUE)", or "(code 2067)" on older versions.
    private static final Pattern CODE = Pattern.compile("\\(code (\\d+)");
    private static final String[] UNIQUE_MARKERS = {"UNIQUE constraint failed", "PRIMARY KEY must be unique"};
    private static final int SQLITE_CONSTRAINT_FOREIGNKEY = 787;
    private static final String FOREIGN_KEY_MARKER = "FOREIGN KEY constraint failed";

    private AndroidSqliteFailures() {
    }

    static DriverException translate(RuntimeException failure) {
        String text = failure.getMessage() == null ? "" : failure.getMessage();
        int code = code(text);
        Set<DriverFailureKind> kinds = EnumSet.noneOf(DriverFailureKind.class);
        if (failure instanceof SQLiteConstraintException && isUnique(code, text)) {
            kinds.add(DriverFailureKind.UNIQUE_VIOLATION);
        }
        if (failure instanceof SQLiteConstraintException
                && (code == SQLITE_CONSTRAINT_FOREIGNKEY || text.contains(FOREIGN_KEY_MARKER))) {
            kinds.add(DriverFailureKind.FOREIGN_KEY_VIOLATION);
        }
        if (failure instanceof SQLiteDatabaseLockedException
                || (code & PRIMARY_CODE_MASK) == SQLITE_BUSY || (code & PRIMARY_CODE_MASK) == SQLITE_LOCKED) {
            kinds.add(DriverFailureKind.CONCURRENCY);
        }
        String codeName = DriverFailureMessages.sqliteCodeName(code).orElse(null);
        return new DriverException(DriverFailureMessages.message(kinds, null, code, codeName), null, code,
                failure.getMessage(), failure, kinds);
    }

    static boolean isSqliteFailure(RuntimeException failure) {
        return failure instanceof SQLiteException;
    }

    private static int code(String text) {
        Matcher matcher = CODE.matcher(text);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isUnique(int code, String text) {
        if (code == SQLITE_CONSTRAINT_UNIQUE || code == SQLITE_CONSTRAINT_PRIMARYKEY) {
            return true;
        }
        for (String marker : UNIQUE_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
