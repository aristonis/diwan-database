package org.aristonis.diwan.database.testing;

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.aristonis.diwan.database.query.QueryFactory;

/**
 * The database of one test in template mode: a copy of the template, and the manager open on it.
 *
 * <p>{@link #close()} closes the manager, then removes the copy. A failure of either is logged at
 * {@code WARNING} with the copy's name and never thrown, so a copy that cannot be dropped does not
 * change the test's result; the next run's sweep removes it.
 */
final class TestDatabase {

    /** The key under which the extension keeps the test's database in its own store. */
    static final String KEY = "testDatabase";

    private static final Logger LOGGER = Logger.getLogger(TestDatabase.class.getName());

    private final String name;
    private final DatabaseManager manager;
    private final Consumer<String> dropper;

    /**
     * Wraps a copy and its manager.
     *
     * @param name the copy's name, never {@code null}
     * @param manager the manager open on it, or {@code null} when opening failed and only the copy
     *     is left to remove
     * @param dropper removes a copy by name, never {@code null}
     */
    TestDatabase(String name, DatabaseManager manager, Consumer<String> dropper) {
        this.name = name;
        this.manager = manager;
        this.dropper = dropper;
    }

    /**
     * Returns the copy's name.
     *
     * @return the name {@code perDatabase} was given, never {@code null}
     */
    String name() {
        return name;
    }

    /**
     * Returns the query factory of the copy.
     *
     * @return the factory, never {@code null}
     * @throws IllegalStateException if no manager was opened on the copy
     */
    QueryFactory query() {
        return manager().query();
    }

    /**
     * Returns the connection of the copy.
     *
     * @return the connection, never {@code null}
     * @throws IllegalStateException if no manager was opened on the copy
     */
    Connection connection() {
        return manager().connection();
    }

    private DatabaseManager manager() {
        if (manager == null) {
            throw new IllegalStateException("No manager was opened on the copy '" + name + "'");
        }
        return manager;
    }

    /** Closes the manager and removes the copy; failures are logged, never thrown. */
    void close() {
        try {
            if (manager != null) {
                manager.close();
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not close the manager on the test copy '" + name + "'", e);
        }
        try {
            dropper.accept(name);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not drop the test copy '" + name
                    + "'; the next run's sweep removes it", e);
        }
    }
}
