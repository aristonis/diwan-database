package org.aristonis.diwan.database.seeding;

import java.time.Duration;

/**
 * Observes the seeders of one run, such as to print progress.
 *
 * <p>Called on the thread that runs the seeders, in call order. Seeders called silently are not
 * reported. A listener must not throw: its exception would replace the seeder's own outcome.
 */
public interface SeederListener {

    /** A listener that ignores every event. */
    SeederListener NONE = new SeederListener() {
    };

    /**
     * Reports that a seeder is about to run.
     *
     * @param seeder the class of the seeder, never {@code null}
     */
    default void started(Class<?> seeder) {
    }

    /**
     * Reports that a seeder ran to the end.
     *
     * @param seeder the class of the seeder, never {@code null}
     * @param elapsed how long it ran, nested seeders included, never {@code null}
     */
    default void finished(Class<?> seeder, Duration elapsed) {
    }

    /**
     * Reports that a seeder threw. A seeder that called the failing one is reported too, innermost first.
     *
     * @param seeder the class of the seeder, never {@code null}
     * @param failure what it threw, never {@code null}
     */
    default void failed(Class<?> seeder, Throwable failure) {
    }
}
