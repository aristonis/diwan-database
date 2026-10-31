package org.aristonis.diwan.database;

import java.util.List;
import org.aristonis.diwan.database.driver.DriverException;

/**
 * Reports a statement that broke a foreign key constraint, as classified by the driver.
 *
 * <p>Instances are immutable after construction and safe to share between threads.
 */
public final class ForeignKeyViolationException extends QueryException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a foreign key failure from the driver failure that caused it.
     *
     * @param connectionName the name of the connection, never {@code null}
     * @param sql the statement that failed, never {@code null}
     * @param bindings the binding values, never {@code null}, elements may be {@code null}; the
     *     list is copied
     * @param cause the driver failure, never {@code null}
     * @param showBindings whether the binding values are appended to the message
     * @throws NullPointerException if any reference argument is {@code null}
     */
    public ForeignKeyViolationException(
            String connectionName, String sql, List<?> bindings, DriverException cause, boolean showBindings) {
        super(connectionName, sql, bindings, cause, showBindings);
    }
}
