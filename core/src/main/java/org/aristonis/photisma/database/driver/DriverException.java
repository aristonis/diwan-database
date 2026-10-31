package org.aristonis.photisma.database.driver;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Reports a failure raised by a driver, with the database's SQL state, vendor code and kind.
 *
 * <p>Instances are immutable after construction and safe to share between threads.
 *
 * @implSpec A driver must build the message from a fixed description of the failure plus the SQL
 *     state and vendor code, never from bound values and never from the database's raw error text,
 *     which often echoes the offending values. The core keeps binding values out of every message it
 *     builds and shows them only when the application opts in; a message that carries them would
 *     defeat that. The database's own exception may be passed as the cause.
 */
public final class DriverException extends RuntimeException {

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
        super(message, cause);
        Objects.requireNonNull(kinds, "kinds");
        // EnumSet.copyOf(Collection) throws on an empty set that is not an EnumSet.
        EnumSet<DriverFailureKind> copy = EnumSet.noneOf(DriverFailureKind.class);
        for (DriverFailureKind kind : kinds) {
            copy.add(Objects.requireNonNull(kind, "kind"));
        }
        this.sqlState = sqlState;
        this.vendorCode = vendorCode;
        this.kinds = copy;
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
     * Tells whether the driver classified this failure as a concurrency failure.
     *
     * @return {@code true} if the kinds include {@link DriverFailureKind#CONCURRENCY}
     */
    public boolean isConcurrencyFailure() {
        return kinds.contains(DriverFailureKind.CONCURRENCY);
    }
}
