package org.aristonis.diwan.database.config;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.aristonis.diwan.database.exception.DatabaseConfigException;

/**
 * Parses the {@code lock_wait} connection option into a number of milliseconds.
 *
 * <p>The value is a whole number followed by an optional unit: {@code ms} for milliseconds, {@code s}
 * for seconds, or nothing for milliseconds. The unit is case-insensitive and surrounding whitespace is
 * ignored. The result must be positive and fit in an {@code int} number of milliseconds. Error messages
 * name the connection and the key, never the value.
 */
public final class LockWaitOption {

    /** The config key this option is read from. */
    public static final String KEY = "lock_wait";

    /** Accepts digits with an optional {@code ms} or {@code s} unit; the unit group is empty when absent. */
    private static final Pattern DURATION = Pattern.compile("([0-9]+)(ms|s|)");

    /** The unit name for seconds. */
    private static final String SECONDS_UNIT = "s";

    /** The number of milliseconds in one second. */
    private static final long MILLIS_PER_SECOND = 1000L;

    /** The largest accepted value, in milliseconds. */
    private static final long MAX_MILLIS = Integer.MAX_VALUE;

    private LockWaitOption() {
    }

    /**
     * Parses one {@code lock_wait} value.
     *
     * @param connectionName the connection the value belongs to, never {@code null}
     * @param key the config key the value was read from, never {@code null}
     * @param value the value as written, such as {@code 5s}, {@code 500ms} or {@code 250}, never {@code null}
     * @return the wait in milliseconds, from 1 to {@link Integer#MAX_VALUE}
     * @throws NullPointerException if any argument is {@code null}
     * @throws DatabaseConfigException if the value is blank, not a number with an optional unit, zero,
     *     or longer than {@link Integer#MAX_VALUE} milliseconds
     */
    public static long parseMillis(String connectionName, String key, String value) {
        Objects.requireNonNull(connectionName, "connectionName");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Matcher matcher = DURATION.matcher(value.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw invalid(connectionName, key);
        }
        long unit = unitMillis(matcher.group(2));
        long amount = parseAmount(matcher.group(1), connectionName, key);
        if (amount <= 0 || amount > MAX_MILLIS / unit) {
            throw invalid(connectionName, key);
        }
        return amount * unit;
    }

    /** Returns the milliseconds in one unit of the given suffix; an empty suffix means milliseconds. */
    private static long unitMillis(String suffix) {
        return SECONDS_UNIT.equals(suffix) ? MILLIS_PER_SECOND : 1L;
    }

    /** Parses the digits, turning a number too long for a {@code long} into the same error. */
    private static long parseAmount(String digits, String connectionName, String key) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            throw invalid(connectionName, key);
        }
    }

    private static DatabaseConfigException invalid(String connectionName, String key) {
        return new DatabaseConfigException("Connection [" + connectionName + "] key [" + key
                + "] must be a positive duration such as 5s, 500ms or 250, at most "
                + MAX_MILLIS + "ms", connectionName, key);
    }
}
