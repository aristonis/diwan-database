package org.aristonis.photisma.database;

/**
 * Runs work whose SQL is recorded instead of sent to the database.
 *
 * @see Connection#pretend(PretendCallback)
 */
@FunctionalInterface
public interface PretendCallback {

    /**
     * Runs the work on the recording connection.
     *
     * @apiNote Only unchecked exceptions may be thrown.
     * @param connection the connection that records every statement, never {@code null}
     * @throws RuntimeException any failure of the work
     */
    void run(Connection connection);
}
