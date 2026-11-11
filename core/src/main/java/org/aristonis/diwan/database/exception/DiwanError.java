package org.aristonis.diwan.database.exception;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.aristonis.diwan.database.Internal;
import org.aristonis.diwan.database.driver.DiwanErrorCodes;
import org.aristonis.diwan.database.driver.DriverFailureKind;

/**
 * Describes a database failure in the same terms on every database: a stable code, and the name of
 * the constraint and the columns involved when the database told.
 *
 * <p>The code has the form {@code DWN-<group>-<number>}, for example {@code DWN-CST-0001} for a
 * unique violation. Codes are public API: they are never renumbered or reused, and a failure with
 * several kinds reports the code of the first kind in the order unique, foreign key, check, not
 * null, concurrency, commit outcome unknown, connection lost, cancelled. A failure Diwan does not
 * recognise has {@code DWN-UNK-0000}.
 *
 * <p>The key and columns are schema names, never values. Instances are immutable and safe to share
 * between threads.
 */
public final class DiwanError implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The Diwan error code, such as {@code DWN-CST-0001}. */
    private final String code;
    /** The constraint or index name, or {@code null} when the database gave none. */
    private final String key;
    /** The column names the database named, possibly empty. */
    private final ArrayList<String> columns;
    /** The table name, or {@code null} when the database gave none; {@code null} in streams written before it existed. */
    private final String table;

    private DiwanError(String code, String key, String table, List<String> columns) {
        this.code = code;
        this.key = key;
        this.table = table;
        this.columns = new ArrayList<>(columns);
    }

    /**
     * Builds the error for the kinds of a failure.
     *
     * @apiNote Internal: for drivers in this library.
     * @param kinds the kinds the driver recognised, may be {@code null} or empty
     * @param key the constraint or index name, or {@code null} when there is none
     * @param columns the column names, never {@code null}, may be empty; the list is copied
     * @return the error, never {@code null}
     * @throws NullPointerException if {@code columns} or one of its elements is {@code null}
     */
    @Internal
    public static DiwanError of(Set<DriverFailureKind> kinds, String key, List<String> columns) {
        return of(kinds, key, null, columns);
    }

    /**
     * Builds the error for the kinds of a failure, with the table.
     *
     * @apiNote Internal: for drivers in this library.
     * @param kinds the kinds the driver recognised, may be {@code null} or empty
     * @param key the constraint or index name, or {@code null} when there is none
     * @param table the table name, or {@code null} when there is none
     * @param columns the column names, never {@code null}, may be empty; the list is copied
     * @return the error, never {@code null}
     * @throws NullPointerException if {@code columns} or one of its elements is {@code null}
     */
    @Internal
    public static DiwanError of(Set<DriverFailureKind> kinds, String key, String table, List<String> columns) {
        Objects.requireNonNull(columns, "columns");
        for (String column : columns) {
            Objects.requireNonNull(column, "column");
        }
        return new DiwanError(DiwanErrorCodes.codeOf(kinds), key, table, columns);
    }

    /**
     * Returns the stable code of the failure.
     *
     * @return the code such as {@code DWN-CST-0001}, never {@code null}
     */
    public String code() {
        return code;
    }

    /**
     * Returns the name of the constraint or index that failed.
     *
     * @return the name, or an empty optional when the database gave none
     */
    public Optional<String> key() {
        return Optional.ofNullable(key);
    }

    /**
     * Returns the name of the table the failure concerns.
     *
     * @return the name, or an empty optional when the database gave none
     */
    public Optional<String> table() {
        return Optional.ofNullable(table);
    }

    /**
     * Returns the names of the columns involved.
     *
     * @return an unmodifiable list, possibly empty, never {@code null}
     */
    public List<String> columns() {
        return Collections.unmodifiableList(columns);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DiwanError)) {
            return false;
        }
        DiwanError that = (DiwanError) other;
        return code.equals(that.code) && Objects.equals(key, that.key)
                && Objects.equals(table, that.table) && columns.equals(that.columns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, key, table, columns);
    }

    @Override
    public String toString() {
        return "DiwanError{code=" + code + ", key=" + key + ", table=" + table + ", columns=" + columns + "}";
    }

    /**
     * Reads the fields and refuses a stream that breaks the rules {@link #of} enforces.
     *
     * @param in the stream to read from
     * @throws IOException if the stream cannot be read, or holds a {@code null} code or column list
     *     or a {@code null} column name
     * @throws ClassNotFoundException if a class in the stream cannot be found
     */
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        if (code == null || columns == null || columns.contains(null)) {
            throw new InvalidObjectException("DiwanError needs a code and non-null column names");
        }
    }
}
