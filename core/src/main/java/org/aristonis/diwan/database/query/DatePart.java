package org.aristonis.diwan.database.query;

import java.util.Locale;
import org.aristonis.diwan.database.Internal;

/**
 * The part of a date or time a {@link Where.DateBased} condition compares.
 *
 * @apiNote Internal: read by dialect grammars; may change without notice.
 */
@Internal
public enum DatePart {
    /** The date. */
    DATE,
    /** The time of day. */
    TIME,
    /** The day of the month. */
    DAY,
    /** The month. */
    MONTH,
    /** The year. */
    YEAR;

    /**
     * Returns the name Laravel's base grammar uses as the SQL function.
     *
     * @return the lower-case name, never {@code null}
     */
    public String sqlName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
