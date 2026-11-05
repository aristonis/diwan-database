package org.aristonis.diwan.database.manager;

/**
 * Receives the events of a {@link DatabaseManager}, as opposed to the events of one connection.
 *
 * <p>Every method has an empty default body, so an implementation overrides only what it needs.
 * A listener is registered with {@link DatabaseManager.Builder#managerListener(ManagerListener)}.
 *
 * <p>A listener must not call the manager, for any connection name. It runs while a holder lock is
 * held, so two listeners that ask for each other's connection on different threads can deadlock.
 *
 * @implSpec An implementation must be safe to call from any thread that asks the manager for a
 *     connection, and should return quickly, because it runs while that connection is being built.
 */
public interface ManagerListener {

    /**
     * Called after the manager built a connection and attached its listeners, before any caller can
     * see the connection.
     *
     * <p>It fires again when a connection is built anew after {@code purge}, {@code disconnect} or
     * {@code reconnect}. The listener may use the event's connection, but must not ask the manager
     * for the connection being built: that throws {@link IllegalStateException}.
     *
     * @param event the connection that was built, never {@code null}
     * @throws RuntimeException if the listener fails; the manager then closes the new connection
     *     and the failure reaches the caller that asked for it, and the next call builds again
     */
    default void connectionEstablished(ConnectionEstablished event) {
    }
}
