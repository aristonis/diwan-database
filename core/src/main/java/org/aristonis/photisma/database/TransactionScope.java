package org.aristonis.photisma.database;

/**
 * Holds a transaction open until it is committed or closed, for use with try-with-resources.
 *
 * <p>{@link #close()} rolls back when {@link #commit()} was not called or failed. Closing twice does
 * nothing. Any call after the scope ended fails with {@link IllegalStateException}. Closing an outer
 * scope while an inner one is open ends the inner one first. A scope belongs to the thread that
 * created it and is not thread-safe.
 */
public interface TransactionScope extends Connection, AutoCloseable {

    /**
     * Commits the work of this scope; for a nested scope, keeps it inside the enclosing one.
     *
     * @throws IllegalStateException if the scope already ended
     * @throws QueryException if the database refuses the commit
     */
    void commit();

    /**
     * Ends the scope, rolling back unless {@link #commit()} succeeded.
     *
     * @throws QueryException if the rollback fails
     */
    @Override
    void close();
}
