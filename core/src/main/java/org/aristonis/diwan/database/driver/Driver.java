package org.aristonis.diwan.database.driver;

/**
 * Opens sessions on one database for Diwan Database.
 *
 * <p>Operations added in later versions arrive as {@code default} methods, so an existing driver
 * keeps compiling. A driver has no lifecycle method: shutting down whatever it draws connections
 * from, such as a pool, is the job of whoever owns that pool.
 *
 * @implSpec Implementations must be safe to call from several threads at once; each call to
 *     {@link #open()} returns a new session that is used by one thread at a time.
 */
public interface Driver {

    /**
     * Opens a session on one physical connection.
     *
     * @implSpec This method may block while waiting for a connection, for example from a pool. A
     *     wait that times out must be reported as a {@link DriverException}.
     * @return a new open session, never {@code null}; the caller must close it
     * @throws DriverException if no connection can be obtained, including on a timeout
     */
    DriverSession open();

    /**
     * Describes the database this driver talks to.
     *
     * @implSpec This method must be cheap, because the core may call it often. Compute the
     *     description once and cache it; any I/O may happen only on the first call.
     * @return the database description, never {@code null}
     * @throws DriverException if the description cannot be read
     */
    DriverInfo info();
}
