package org.aristonis.diwan.database.migrations;

/**
 * Observes what a {@link Migrator} does, as Laravel's migration events do, plus a failure event.
 *
 * <p>Every method has an empty default, so a listener overrides only what it needs. Register one
 * with {@link Migrator.Builder#listener(MigrationListener)}; listeners are called in registration
 * order on the thread that runs the migrator.
 *
 * <p>What a throwing listener does depends on the event:
 *
 * <ul>
 *   <li>{@link #migrationsStarted} and {@link #migrationStarted}: the exception stops the call
 *       before that work starts. Migrations that already finished stay applied and recorded.
 *   <li>{@link #migrationEnded}, {@link #migrationSkipped}, {@link #migrationsEnded} and
 *       {@link #noPendingMigrations}: the exception propagates after the work it reports is done and
 *       recorded, so the migrations table stays correct. The call ends there.
 *   <li>{@link #migrationFailed}: the exception is added as suppressed to the
 *       {@link MigrationFailedException} that is thrown, and the remaining listeners still run.
 * </ul>
 *
 * <p>A failed migration ends the call with {@link #migrationFailed}; {@link #migrationsEnded} is not
 * called then. Events of a pretend call carry {@link MigrationEvent#isPretend()} and are fired
 * outside the pretend scope, so statements a listener runs are not collected.
 */
public interface MigrationListener {

    /**
     * Called once before the first migration of a run, rollback or reset, inside the migration lock.
     *
     * @param event the call as a whole, never {@code null}
     */
    default void migrationsStarted(MigrationsEvent event) {
    }

    /**
     * Called once after the last migration of a call that succeeded.
     *
     * @param event the call as a whole, never {@code null}
     */
    default void migrationsEnded(MigrationsEvent event) {
    }

    /**
     * Called instead of {@link #migrationsStarted} when there is nothing to run or undo.
     *
     * @param event the call as a whole, never {@code null}
     */
    default void noPendingMigrations(MigrationsEvent event) {
    }

    /**
     * Called before one migration runs, before its transaction opens.
     *
     * @param event the migration, never {@code null}
     */
    default void migrationStarted(MigrationEvent event) {
    }

    /**
     * Called after one migration ran and its record was written or removed.
     *
     * @param event the migration, never {@code null}
     */
    default void migrationEnded(MigrationEvent event) {
    }

    /**
     * Called when a migration's {@code shouldRun()} returned {@code false}; going up only.
     *
     * @param event the migration, never {@code null}
     */
    default void migrationSkipped(MigrationEvent event) {
    }

    /**
     * Called when a migration, or the record of it, failed, just before the failure is thrown.
     *
     * <p>It is not called for the first attempt of a migration that is run again with foreign-key
     * enforcement off, only if the second attempt fails.
     *
     * @param event the migration, never {@code null}
     * @param failure the {@link MigrationFailedException} about to be thrown, never {@code null}
     */
    default void migrationFailed(MigrationEvent event, Throwable failure) {
    }
}
