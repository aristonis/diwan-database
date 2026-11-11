package org.aristonis.diwan.database.testing;

import java.util.function.Consumer;
import java.util.function.Function;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.manager.DatabaseManager;

/**
 * How one database engine builds a template and hands out a copy of it per test.
 *
 * <p>One instance serves one run and one template. {@link TemplateDatabases} calls it in a fixed
 * order: {@link #prepare}, then {@link #sweep}, then any number of {@link #createCopy},
 * {@link #afterOpen} and {@link #dropCopy} rounds, then {@link #close}. It serialises every call
 * after {@code prepare}, so an implementation may use the run's single root connection without
 * further locking.
 *
 * @implSpec An implementation owns every statement on the root connection: creating and dropping
 *     databases, advisory or file locks, the sweep. It never leaves a half-built template under the
 *     final template name, never drops a copy of a run other than its own while that run is alive,
 *     and reports a failure with an exception that names the database involved.
 */
interface TemplateCopies extends AutoCloseable {

    /**
     * Builds the template, or finds the one an earlier or concurrent run built, and records that
     * this run is alive.
     *
     * @param perDatabase opens a manager on the database of the given name, never {@code null};
     *     the caller closes the manager it gets
     * @param migrate migrates the database behind the given connection, never {@code null}
     * @throws IllegalStateException if the template cannot be built or the engine refuses
     */
    void prepare(Function<String, DatabaseManager> perDatabase, Consumer<Connection> migrate);

    /**
     * Removes the copies and build databases that a run which is gone left behind. Never touches
     * this run's own, nor a live run's.
     */
    void sweep();

    /**
     * Creates the copy for one test.
     *
     * @param n the copy's number in this run, one or more, never repeated
     * @return the name to give {@code perDatabase}, which {@link #dropCopy} later takes back, never
     *     {@code null}
     * @throws IllegalStateException if the copy cannot be made
     */
    String createCopy(long n);

    /**
     * Checks a copy right after {@code perDatabase} opened it. The default accepts every copy.
     *
     * @param copy the name {@link #createCopy} returned, never {@code null}
     * @param manager the manager {@code perDatabase} returned, never {@code null}
     * @throws IllegalStateException if the manager did not open the copy
     */
    default void afterOpen(String copy, DatabaseManager manager) {
    }

    /**
     * Removes a copy. The manager on it is already closed.
     *
     * @param copy the name {@link #createCopy} returned, never {@code null}
     * @throws IllegalStateException if the copy cannot be removed; the caller logs it and goes on
     */
    void dropCopy(String copy);

    /** Ends this run's claim: releases locks and removes what only this run needed. */
    @Override
    void close();

    /** Creates the {@link TemplateCopies} of a run from the settings and the run's root connection. */
    @FunctionalInterface
    interface Factory {

        /**
         * Creates the copies of one run.
         *
         * @param settings the extension's settings, never {@code null}
         * @param run the run's root connection, never {@code null}
         * @return the copies, never {@code null}
         * @throws IllegalStateException if the target is not supported, for example an in-memory
         *     SQLite database
         */
        TemplateCopies create(TemplateSettings settings, RunConnection run);
    }

    /**
     * Opens a database through {@code perDatabase}, migrates it and closes the manager again.
     *
     * @param perDatabase opens a manager on a database name, never {@code null}
     * @param database the name to open, never {@code null}
     * @param migrate migrates the database behind a connection, never {@code null}
     * @throws IllegalStateException if {@code perDatabase} returns {@code null}
     */
    static void migrateInto(Function<String, DatabaseManager> perDatabase, String database,
            Consumer<Connection> migrate) {
        DatabaseManager manager = perDatabase.apply(database);
        if (manager == null) {
            throw new IllegalStateException("perDatabase returned null for the database '" + database + "'");
        }
        try {
            migrate.accept(manager.connection());
        } finally {
            manager.close();
        }
    }
}
