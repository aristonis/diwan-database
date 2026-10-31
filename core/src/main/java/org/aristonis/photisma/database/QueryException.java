package org.aristonis.photisma.database;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.aristonis.photisma.database.driver.DriverException;

/**
 * Reports a statement the database refused, with the connection name, SQL and bindings.
 *
 * <p>The message is {@code SQL: <sql> (Connection: <name>, SQL state <state>)}, where the state is
 * {@code unknown} when the database gave none. The binding values are appended to the message only
 * when the connection was built with {@code showBindingsInErrors(true)}, because they can hold
 * personal data; {@link #getBindings()} carries them either way. In the message, the SQL, the
 * connection name, the SQL state and the values are escaped: a backslash is doubled, line breaks
 * appear as {@code \r} and {@code \n}, and other control characters and Unicode line separators
 * as <code>&#92;uXXXX</code>. The SQL is cut after 1000 code points and the name, the SQL state and
 * each value after 256 (all marked with {@code ...}), and a {@code byte[]} value appears as
 * {@code <n bytes>}.
 *
 * <p>The database's own error text is kept only in the {@linkplain #getCause() cause}, never in this
 * message, because databases often echo the offending values in it.
 *
 * <p>The serialized form leaves out the binding values when they were hidden from the message, and
 * after deserialization {@link #getBindings()} is then empty. Only the bindings field is left out:
 * the {@linkplain #getCause() cause} (the driver failure, which may hold the database's own error
 * text) and any suppressed exceptions are serialized as usual.
 *
 * <p>Instances are immutable after construction and safe to share between threads.
 */
public class QueryException extends RuntimeException {

    private static final long serialVersionUID = 2L;

    /** The name of the connection the statement ran on, never {@code null}. */
    private final String connectionName;
    /** The statement that failed, never {@code null}. */
    private final String sql;
    /** The binding values in positional order; written by {@code writeObject} only when shown. */
    private transient List<Object> bindings;
    /** Whether the binding values are shown in the message and kept in the serialized form. */
    private final boolean showBindings;
    /** The SQL state the database reported, or {@code null} when it gave none. */
    private final String sqlState;
    /** The vendor-specific error code. */
    private final int vendorCode;
    /** Whether the driver classified the failure as a concurrency failure. */
    private final boolean concurrencyFailure;

    /**
     * Creates a query failure from the driver failure that caused it.
     *
     * @param connectionName the name of the connection, never {@code null}
     * @param sql the statement that failed, never {@code null}
     * @param bindings the binding values, never {@code null}, elements may be {@code null}; the
     *     list is copied
     * @param cause the driver failure, never {@code null}
     * @param showBindings whether the binding values are appended to the message
     * @throws NullPointerException if any reference argument is {@code null}
     */
    public QueryException(
            String connectionName, String sql, List<?> bindings, DriverException cause, boolean showBindings) {
        super(message(connectionName, sql, bindings, cause, showBindings), cause);
        this.connectionName = connectionName;
        this.sql = sql;
        this.bindings = Bindings.copyArrays(bindings);
        this.showBindings = showBindings;
        this.sqlState = cause.sqlState().orElse(null);
        this.vendorCode = cause.vendorCode();
        this.concurrencyFailure = cause.isConcurrencyFailure();
    }

    private static String message(
            String connectionName, String sql, List<?> bindings, DriverException cause, boolean showBindings) {
        Objects.requireNonNull(connectionName, "connectionName");
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(cause, "cause");
        String base = "SQL: " + MessageText.sql(sql) + " (Connection: " + MessageText.name(connectionName)
                + ", SQL state " + MessageText.name(cause.sqlState().orElse("unknown")) + ")";
        return showBindings ? base + " Bindings: " + MessageText.values(bindings) : base;
    }

    /**
     * Writes the fields, then the bindings when they are shown.
     *
     * @serialData the default fields, followed by an {@code ArrayList} of the binding values only
     *     when {@code showBindings} is {@code true}
     * @param out the stream to write to
     * @throws IOException if writing fails
     */
    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        if (showBindings) {
            out.writeObject(new ArrayList<Object>(bindings));
        }
    }

    /**
     * Reads the fields and, when they were shown, the bindings.
     *
     * @param in the stream to read from
     * @throws IOException if reading fails or the stream holds an invalid form
     * @throws ClassNotFoundException if a class in the stream cannot be found
     */
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        if (connectionName == null || sql == null) {
            throw new InvalidObjectException("connectionName and sql must not be null");
        }
        if (!showBindings) {
            bindings = Collections.emptyList();
            return;
        }
        Object read = in.readObject();
        if (!(read instanceof List)) {
            throw new InvalidObjectException("bindings must be a list");
        }
        bindings = Bindings.copyArrays((List<?>) read);
    }

    /**
     * Returns the name of the connection the statement ran on.
     *
     * @return the connection name, never {@code null}
     */
    public String getConnectionName() {
        return connectionName;
    }

    /**
     * Returns the statement that failed.
     *
     * @return the SQL, never {@code null}
     */
    public String getSql() {
        return sql;
    }

    /**
     * Returns the binding values of the failed statement, whatever the message shows.
     *
     * <p>After deserialization the list is empty when the values were hidden from the message.
     *
     * @return an unmodifiable list in positional order, never {@code null}, elements may be
     *     {@code null}; {@code byte[]} values are copies
     */
    public List<Object> getBindings() {
        return Collections.unmodifiableList(Bindings.copyArrays(bindings));
    }

    /**
     * Returns the SQL state the database reported.
     *
     * @return the SQL state, or an empty optional when the database gave none
     */
    public Optional<String> sqlState() {
        return Optional.ofNullable(sqlState);
    }

    /**
     * Returns the vendor-specific error code.
     *
     * @return the vendor code, {@code 0} when there is none
     */
    public int vendorCode() {
        return vendorCode;
    }

    /**
     * Tells whether the driver classified the failure as a deadlock, serialization failure, or busy
     * or locked database.
     *
     * @return {@code true} if the failure is retryable at the outermost transaction
     */
    public boolean isConcurrencyFailure() {
        return concurrencyFailure;
    }
}
