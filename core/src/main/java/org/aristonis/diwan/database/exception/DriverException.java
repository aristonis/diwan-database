package org.aristonis.diwan.database.exception;

import org.aristonis.diwan.database.driver.DriverFailureKind;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Reports a failure raised by a driver, with the database's SQL state, vendor code and kind.
 *
 * <p>The message names the SQL state and vendor code, then the recognised kinds and, on SQLite, the
 * result code name, such as {@code SQLITE_BUSY}. It never holds bound values or the database's own
 * error text.
 *
 * <p>Instances are immutable after construction and safe to share between threads.
 *
 * @implSpec A driver must build the message from a fixed description of the failure plus the SQL
 *     state and vendor code, never from bound values and never from the database's raw error text,
 *     which often echoes the offending values. The core keeps binding values out of every message it
 *     builds and shows them only when the application opts in; a message that carries them would
 *     defeat that. The raw text goes in {@link #databaseMessage()} instead, which the core shows
 *     under the same opt-in. The database's own exception may be passed as the cause.
 */
public final class DriverException extends DatabaseException {

    private static final long serialVersionUID = 1L;

    /** The SQL state the driver reported, or {@code null} when it reported none. */
    private final String sqlState;
    /** The database vendor's own error code. */
    private final int vendorCode;
    /**
     * The kinds the driver assigned to this failure, possibly empty, never {@code null}.
     *
     * <p>Declared as {@link EnumSet} because that type is serializable, so the kinds survive the
     * serial form. The set is a private copy that is never modified after construction; callers see
     * it only through the unmodifiable view {@link #kinds()} returns.
     */
    private final EnumSet<DriverFailureKind> kinds;
    /**
     * The database's own error text, or {@code null}. It may echo values, so it is shown only on
     * opt-in and this field is left out of the serialized form; the cause, when a driver passes the
     * database's own exception, still carries the same text.
     */
    private final transient String databaseMessage;
    /**
     * The unified error, or {@code null} in a stream written before it existed; {@link #error()}
     * then derives it from the kinds.
     */
    private final DiwanError error;

    /**
     * Creates a driver failure.
     *
     * @param message the detail message, may be {@code null}
     * @param sqlState the SQL state the database reported, or {@code null} when it gave none
     * @param vendorCode the vendor-specific error code, {@code 0} when there is none
     * @param cause the underlying failure, or {@code null} when there is none
     * @param kinds the kinds the driver assigns to this failure, never {@code null}, may be empty;
     *     the set is copied and later changes to it have no effect
     * @throws NullPointerException if {@code kinds} or any element of it is {@code null}
     */
    public DriverException(
            String message, String sqlState, int vendorCode, Throwable cause, Set<DriverFailureKind> kinds) {
        this(message, sqlState, vendorCode, null, cause, kinds);
    }

    /**
     * Creates a driver failure that keeps the database's own error text.
     *
     * @param message the detail message, may be {@code null}; must not contain the database text
     * @param sqlState the SQL state the database reported, or {@code null} when it gave none
     * @param vendorCode the vendor-specific error code, {@code 0} when there is none
     * @param databaseMessage the database's own error text, or {@code null} when there is none
     * @param cause the underlying failure, or {@code null} when there is none
     * @param kinds the kinds the driver assigns to this failure, never {@code null}, may be empty;
     *     the set is copied and later changes to it have no effect
     * @throws NullPointerException if {@code kinds} or any element of it is {@code null}
     */
    public DriverException(String message, String sqlState, int vendorCode, String databaseMessage,
            Throwable cause, Set<DriverFailureKind> kinds) {
        this(message, sqlState, vendorCode, databaseMessage, cause, kinds, null, Collections.<String>emptyList());
    }

    /**
     * Creates a driver failure with the constraint name and columns the database reported.
     *
     * @param message the detail message, may be {@code null}; must not contain the database text
     * @param sqlState the SQL state the database reported, or {@code null} when it gave none
     * @param vendorCode the vendor-specific error code, {@code 0} when there is none
     * @param databaseMessage the database's own error text, or {@code null} when there is none
     * @param cause the underlying failure, or {@code null} when there is none
     * @param kinds the kinds the driver assigns to this failure, never {@code null}, may be empty;
     *     the set is copied and later changes to it have no effect
     * @param key the constraint or index name, or {@code null} when there is none
     * @param columns the column names, never {@code null}, may be empty; the list is copied
     * @throws NullPointerException if {@code kinds}, {@code columns} or any element of them is {@code null}
     */
    public DriverException(String message, String sqlState, int vendorCode, String databaseMessage,
            Throwable cause, Set<DriverFailureKind> kinds, String key, List<String> columns) {
        this(message, sqlState, vendorCode, databaseMessage, cause, kinds, key, null, columns);
    }

    /**
     * Creates a driver failure with the constraint name, table and columns the database reported.
     *
     * @param message the detail message, may be {@code null}; must not contain the database text
     * @param sqlState the SQL state the database reported, or {@code null} when it gave none
     * @param vendorCode the vendor-specific error code, {@code 0} when there is none
     * @param databaseMessage the database's own error text, or {@code null} when there is none
     * @param cause the underlying failure, or {@code null} when there is none
     * @param kinds the kinds the driver assigns to this failure, never {@code null}, may be empty;
     *     the set is copied and later changes to it have no effect
     * @param key the constraint or index name, or {@code null} when there is none
     * @param table the table name, or {@code null} when there is none
     * @param columns the column names, never {@code null}, may be empty; the list is copied
     * @throws NullPointerException if {@code kinds}, {@code columns} or any element of them is {@code null}
     */
    public DriverException(String message, String sqlState, int vendorCode, String databaseMessage,
            Throwable cause, Set<DriverFailureKind> kinds, String key, String table, List<String> columns) {
        super(message, cause);
        Objects.requireNonNull(kinds, "kinds");
        // EnumSet.copyOf(Collection) throws on an empty set that is not an EnumSet.
        EnumSet<DriverFailureKind> copy = EnumSet.noneOf(DriverFailureKind.class);
        for (DriverFailureKind kind : kinds) {
            copy.add(Objects.requireNonNull(kind, "kind"));
        }
        this.sqlState = sqlState;
        this.vendorCode = vendorCode;
        this.databaseMessage = databaseMessage;
        this.kinds = copy;
        this.error = DiwanError.of(copy, key, table, columns);
    }

    /**
     * Creates a driver failure that carries no SQL state and no vendor code.
     *
     * <p>Meant for failures the driver detects itself, such as a duplicate column label, where the
     * database reported nothing. {@link #sqlState()} is empty and {@link #vendorCode()} is {@code 0}.
     *
     * @param message the detail message, may be {@code null}
     * @param kinds the kinds the driver assigns to this failure, never {@code null}, may be empty;
     *     the set is copied and later changes to it have no effect
     * @param cause the underlying failure, or {@code null} when there is none
     * @throws NullPointerException if {@code kinds} or any element of it is {@code null}
     */
    public DriverException(String message, Set<DriverFailureKind> kinds, Throwable cause) {
        this(message, null, 0, cause, kinds);
    }

    /**
     * Returns the database's own error text, such as {@code no such table: users}.
     *
     * <p>The text can echo the values that caused the failure, so it is not part of
     * {@link #getMessage()} and this field is not serialized. The {@linkplain #getCause() cause}
     * may still hold the same text: logging or serializing the whole exception chain shows it,
     * whatever the connection's settings.
     *
     * @return the text, or empty when the database gave none or the exception was deserialized
     */
    public Optional<String> databaseMessage() {
        return Optional.ofNullable(databaseMessage);
    }

    /**
     * Returns the unified error: a stable code, and the constraint name and columns when known.
     *
     * @return the error, never {@code null}; for an exception read from a stream written by an older
     *     version it is derived from the kinds and has no key and no columns
     */
    public DiwanError error() {
        return error != null ? error : DiwanError.of(kinds, null, Collections.<String>emptyList());
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
     * Returns the kinds the driver assigned to this failure.
     *
     * @apiNote Constants may be added to {@link DriverFailureKind} in later versions; callers must
     *     tolerate kinds they do not know.
     * @return an unmodifiable set in declaration order, never {@code null}, possibly empty
     */
    public Set<DriverFailureKind> kinds() {
        return Collections.unmodifiableSet(kinds);
    }

    /**
     * Tells whether the driver classified this failure as a unique constraint violation.
     *
     * @return {@code true} if the kinds include {@link DriverFailureKind#UNIQUE_VIOLATION}
     */
    public boolean isUniqueViolation() {
        return kinds.contains(DriverFailureKind.UNIQUE_VIOLATION);
    }

    /**
     * Tells whether the driver classified this failure as a foreign key constraint violation.
     *
     * @return {@code true} if the kinds include {@link DriverFailureKind#FOREIGN_KEY_VIOLATION}
     */
    public boolean isForeignKeyViolation() {
        return kinds.contains(DriverFailureKind.FOREIGN_KEY_VIOLATION);
    }

    /**
     * Tells whether the driver classified this failure as a concurrency failure.
     *
     * @return {@code true} if the kinds include {@link DriverFailureKind#CONCURRENCY}
     */
    public boolean isConcurrencyFailure() {
        return kinds.contains(DriverFailureKind.CONCURRENCY);
    }
}
