package org.aristonis.diwan.database;

/**
 * Receives the query and transaction events of a connection family.
 *
 * <p>Every method has an empty default body, so an implementation overrides only what it needs.
 * A listener is registered with {@link ConnectionBuilder#listener(ConnectionListener)} or
 * {@link ConnectionEvents#addListener(ConnectionListener)} and sees the events of the root
 * connection and of every session, transaction and scope derived from it.
 *
 * @apiNote While a listener method runs, statements the same thread runs on the connection fire no
 *     events and are not logged, so a listener may use the connection without recursing. A listener
 *     that throws does not stop the others: see each method for what reaches the caller.
 * @implSpec An implementation must be safe to call from any thread that uses the connection, and
 *     should return quickly, because it runs on the caller's thread.
 */
public interface ConnectionListener {

    /**
     * Called after a statement ran successfully.
     *
     * <p>A cursor fires this when it opens, so the event time covers opening only. A pretended
     * statement fires it with a zero time.
     *
     * @param event the statement that ran, never {@code null}
     * @throws RuntimeException if the listener fails; the caller then receives a
     *     {@link ListenerFailedException}, because the statement already succeeded
     */
    default void queryExecuted(QueryExecuted event) {
    }

    /**
     * Called when a statement failed, before the {@link QueryException} reaches the caller.
     *
     * @param event the statement that failed, never {@code null}
     * @throws RuntimeException if the listener fails; the failure is added as a suppressed
     *     exception to the {@link QueryException} and never replaces it
     */
    default void queryFailed(QueryFailed event) {
    }

    /**
     * Called after a transaction or savepoint was opened.
     *
     * @param event the level that began, never {@code null}
     * @throws RuntimeException if the listener fails; the other listeners still run
     */
    default void transactionBeginning(TransactionEvent event) {
    }

    /**
     * Called before a transaction is committed or a savepoint is released.
     *
     * @param event the level about to finish, never {@code null}
     * @throws RuntimeException if the listener fails; the failure propagates at once and the
     *     transaction rolls back
     */
    default void transactionCommitting(TransactionEvent event) {
    }

    /**
     * Called after a transaction was committed or a savepoint was released.
     *
     * @param event the level that finished, never {@code null}
     * @throws RuntimeException if the listener fails; the transaction is already final, the other
     *     listeners still run and the first failure propagates
     */
    default void transactionCommitted(TransactionEvent event) {
    }

    /**
     * Called after a transaction or savepoint was rolled back.
     *
     * @apiNote On the root connection the outermost call happens before the thread leaves the
     *     transaction, so SQL the listener sends on the root connection is refused in strict mode;
     *     run such work from an {@code afterRollback} callback instead. A failed COMMIT whose outcome is unknown also ends here, so the transaction may
     *     have landed.
     * @param event the level that was rolled back, never {@code null}
     * @throws RuntimeException if the listener fails; the transaction is already final, the other
     *     listeners still run and the first failure propagates
     */
    default void transactionRolledBack(TransactionEvent event) {
    }
}
