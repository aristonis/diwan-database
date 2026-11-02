package org.aristonis.diwan.database.eloquent;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * The clock models read their timestamps from, truncated to whole microseconds, the precision
 * every supported database keeps.
 *
 * <p>Immutable and safe to share across threads.
 */
public final class ModelClock {

    private static final ModelClock SYSTEM = new ModelClock(Clock.systemUTC());

    private final Clock clock;

    private ModelClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Returns the clock of the system, in UTC.
     *
     * @return the system clock, never {@code null}
     */
    public static ModelClock system() {
        return SYSTEM;
    }

    /**
     * Returns a model clock that reads the given clock.
     *
     * @param clock the clock, never {@code null}
     * @return the model clock, never {@code null}
     * @throws NullPointerException if {@code clock} is {@code null}
     */
    public static ModelClock of(Clock clock) {
        return new ModelClock(Objects.requireNonNull(clock, "clock"));
    }

    /**
     * Returns a model clock that always reads the given moment, for tests.
     *
     * @param moment the moment, never {@code null}
     * @return the model clock, never {@code null}
     * @throws NullPointerException if {@code moment} is {@code null}
     */
    public static ModelClock fixed(Instant moment) {
        return of(Clock.fixed(Objects.requireNonNull(moment, "moment"), ZoneOffset.UTC));
    }

    /**
     * Returns the current moment truncated to whole microseconds.
     *
     * @return the moment, never {@code null}
     */
    public Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
