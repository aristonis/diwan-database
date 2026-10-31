package org.aristonis.diwan.database;

/**
 * Runs work inside a transaction and returns its result.
 *
 * <p>One callback type serves every transaction call, because a {@code Consumer} overload beside a
 * {@code Function} one would make {@code tx -> tx.insert(...)} ambiguous to the compiler. A callback
 * with no result returns {@code null}.
 *
 * @param <T> the type of the result
 */
@FunctionalInterface
public interface TransactionCallback<T> {

    /**
     * Runs the work on the connection of the transaction.
     *
     * @apiNote Pass {@code connection} on to every layer the work calls; using the root connection
     *     instead would run outside the transaction. Only unchecked exceptions may be thrown.
     * @param connection the connection that belongs to the transaction, never {@code null}
     * @return the result of the work, may be {@code null}
     * @throws RuntimeException any failure of the work, which rolls the transaction back
     */
    T run(Connection connection);
}
