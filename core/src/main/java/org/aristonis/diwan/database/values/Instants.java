package org.aristonis.diwan.database.values;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.aristonis.diwan.database.schema.Blueprint;

/**
 * Converts moments for a {@link Blueprint#instant} column: whole microseconds since the epoch on
 * SQLite, {@code timestamptz} on PostgreSQL.
 *
 * <p>Microseconds are exact, sort as numbers and carry no time zone, so a moment reads back the
 * same on every device. Bind {@link #toMicros(Instant)} on SQLite and the {@code Instant} itself on
 * PostgreSQL; {@link #read(Object)} accepts what either returns.
 */
public final class Instants {

    private Instants() {
    }

    /**
     * Converts a moment to whole microseconds since 1970-01-01T00:00:00Z.
     *
     * @param moment the moment, never {@code null}
     * @return the microseconds
     * @throws NullPointerException if {@code moment} is {@code null}
     * @throws IllegalArgumentException if the moment has a fraction finer than a microsecond, or is
     *     too far from the epoch for a {@code long}
     */
    public static long toMicros(Instant moment) {
        Objects.requireNonNull(moment, "moment");
        if (moment.getNano() % NANOS_PER_MICRO != 0) {
            throw new IllegalArgumentException("The moment is finer than a microsecond; truncate it first");
        }
        try {
            return Math.addExact(Math.multiplyExact(moment.getEpochSecond(), MICROS_PER_SECOND),
                    moment.getNano() / NANOS_PER_MICRO);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("The moment is too far from the epoch for microseconds");
        }
    }

    /**
     * Returns the current moment truncated to whole microseconds, the precision an
     * {@link Blueprint#instant} column keeps, since some clocks report nanoseconds.
     *
     * @return the current moment, never {@code null}
     */
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Reads a stored moment: microseconds ({@code Long}), an {@code OffsetDateTime} or an
     * {@code Instant}.
     *
     * @param stored the value as the connection returned it, never {@code null}
     * @return the moment
     * @throws NullPointerException if {@code stored} is {@code null}
     * @throws IllegalArgumentException if the value is of another type
     */
    public static Instant read(Object stored) {
        Objects.requireNonNull(stored, "stored");
        if (stored instanceof Long || stored instanceof Integer) {
            return Instant.EPOCH.plus(((Number) stored).longValue(), ChronoUnit.MICROS);
        }
        if (stored instanceof OffsetDateTime) {
            return ((OffsetDateTime) stored).toInstant();
        }
        if (stored instanceof Instant) {
            return (Instant) stored;
        }
        throw new IllegalArgumentException("A moment is stored as microseconds or a zoned timestamp, not "
                + stored.getClass().getSimpleName());
    }

    private static final long MICROS_PER_SECOND = 1_000_000L;
    private static final int NANOS_PER_MICRO = 1_000;
}
